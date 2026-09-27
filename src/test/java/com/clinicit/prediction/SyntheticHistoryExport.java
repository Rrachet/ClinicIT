package com.clinicit.prediction;

import com.clinicit.common.domain.BusinessRuleException;
import com.clinicit.prediction.application.WaitTimeDatasetBuilder;
import com.clinicit.queue.api.QueueEntryResponse;
import com.clinicit.queue.domain.QueueStatus;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.beans.factory.annotation.Autowired;

import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.List;
import java.util.PriorityQueue;
import java.util.Random;
import java.util.UUID;

/**
 * Generates the deterministic synthetic clinic history used to develop and evaluate the
 * wait-time model, then exports it with the real {@link WaitTimeDatasetBuilder}.
 *
 * <p>Not part of the normal suite. Run:
 * {@code mvn test -Dtest=SyntheticHistoryExport -Dclinicit.syntheticExport=ml/data/synthetic_wait_times.csv}
 *
 * <p>The history goes through the real appointment and queue services (so it is recorded in
 * {@code operational_events} exactly as live activity would be), driven by a seeded
 * discrete-event simulation: one clinic, four doctors with different consultation lengths,
 * 60 clinic days (Monday–Saturday). Arrivals are heavier in the first half hour and on
 * Mondays; consultations are log-normal, a little shorter when the queue is long; doctors
 * occasionally take a break; 6% of called patients are absent and are skipped, and most of
 * them come back later. Same seed, same file.
 *
 * <p>It is a simulation. Figures measured on it show that the pipeline works and how the
 * model compares with the baseline under these assumptions, not how accurate it will be in
 * a real clinic (docs/AI.md).
 */
@EnabledIfSystemProperty(named = "clinicit.syntheticExport", matches = ".+")
class SyntheticHistoryExport extends PredictionScenario {

    static final long SEED = 20260105L;
    static final LocalDate FIRST_DAY = LocalDate.of(2026, 1, 5);
    static final int CLINIC_DAYS = 60;

    @Autowired WaitTimeDatasetBuilder dataset;

    private record Doctor(com.clinicit.clinic.domain.DoctorProfile profile, double meanMinutes) {}

    private enum Kind { ARRIVAL, FREE, SKIP, START, COMPLETE, REQUEUE }

    private record Event(LocalDateTime at, long order, Kind kind, int doctor, UUID entry) {}

    private final Random random = new Random(SEED);
    private final PriorityQueue<Event> events = new PriorityQueue<>(
            (a, b) -> a.at().equals(b.at()) ? Long.compare(a.order(), b.order()) : a.at().compareTo(b.at()));
    private long order;
    private List<Doctor> team;
    private boolean[] busy;

    @Test
    void export() throws Exception {
        setUpClinic();
        team = List.of(
                new Doctor(drA, 7),
                new Doctor(doctor(clinic, "Dr. B"), 10),
                new Doctor(doctor(clinic, "Dr. C"), 14),
                new Doctor(doctor(clinic, "Dr. D"), 18));

        LocalDate day = FIRST_DAY;
        for (int simulated = 0; simulated < CLINIC_DAYS; day = day.plusDays(1)) {
            if (day.getDayOfWeek() == DayOfWeek.SUNDAY) continue;
            simulate(day);
            simulated++;
        }

        Path out = Path.of(System.getProperty("clinicit.syntheticExport"));
        if (out.getParent() != null) Files.createDirectories(out.getParent());
        var examples = dataset.build(null);
        try (Writer writer = Files.newBufferedWriter(out, StandardCharsets.UTF_8)) {
            dataset.writeCsv(examples, writer);
        }
        System.out.printf("Wrote %d examples from %d clinic days to %s%n", examples.size(), CLINIC_DAYS, out);
    }

    private void simulate(LocalDate day) {
        busy = new boolean[team.size()];
        double load = switch (day.getDayOfWeek()) {
            case MONDAY -> 1.12;
            case SATURDAY -> 1.02;
            default -> 0.90;
        };
        for (int d = 0; d < team.size(); d++) {
            LocalDateTime t = day.atTime(9, 0);
            LocalDateTime lastArrival = day.atTime(12, 30);
            while (true) {
                double meanGap = team.get(d).meanMinutes() / load;
                if (t.toLocalTime().isBefore(LocalTime.of(9, 30))) meanGap /= 1.6; // morning rush
                t = t.plusSeconds(Math.round(exponential(meanGap) * 60));
                if (t.isAfter(lastArrival)) break;
                schedule(t, Kind.ARRIVAL, d, null);
            }
        }

        List<UUID> leftForGood = new ArrayList<>();
        LocalDateTime last = day.atTime(9, 0);
        while (!events.isEmpty()) {
            Event e = events.poll();
            last = e.at();
            clock.set(e.at().atZone(CLINIC_ZONE).toInstant());
            handle(e, leftForGood);
        }
        clock.set(last.plusMinutes(30).atZone(CLINIC_ZONE).toInstant());
        for (UUID entry : leftForGood) {
            queue.markNoShow(desk, entry);
        }
    }

    private void handle(Event e, List<UUID> leftForGood) {
        Doctor doctor = team.get(e.doctor());
        switch (e.kind()) {
            case ARRIVAL -> {
                checkIn(doctor.profile());
                if (!busy[e.doctor()]) {
                    busy[e.doctor()] = true;
                    schedule(e.at(), Kind.FREE, e.doctor(), null);
                }
            }
            case FREE -> {
                QueueEntryResponse called;
                try {
                    called = queue.callNext(desk, doctor.profile().getId());
                } catch (BusinessRuleException empty) {
                    busy[e.doctor()] = false;
                    return;
                }
                if (random.nextDouble() < 0.06) {
                    schedule(e.at().plusMinutes(2), Kind.SKIP, e.doctor(), called.id());
                    if (random.nextDouble() < 0.6) {
                        schedule(e.at().plusSeconds(120 + Math.round(uniform(5, 20) * 60)), Kind.REQUEUE, e.doctor(), called.id());
                    } else {
                        leftForGood.add(called.id());
                    }
                } else {
                    schedule(e.at().plusSeconds(Math.round(uniform(0.5, 1.5) * 60)), Kind.START, e.doctor(), called.id());
                }
            }
            case SKIP -> {
                queue.skip(desk, e.entry());
                schedule(e.at(), Kind.FREE, e.doctor(), null);
            }
            case START -> {
                queue.startConsultation(desk, e.entry());
                long waiting = entries(doctor).stream().filter(q -> q == QueueStatus.WAITING).count();
                double rush = 1 - 0.04 * Math.min(waiting, 5); // a little quicker when the room is full
                double minutes = logNormal(doctor.meanMinutes() * rush, 0.35);
                schedule(e.at().plusSeconds(Math.round(minutes * 60)), Kind.COMPLETE, e.doctor(), e.entry());
            }
            case COMPLETE -> {
                queue.complete(desk, e.entry());
                double gap = random.nextDouble() < 0.04 ? uniform(8, 20) : uniform(0.3, 2.0);
                schedule(e.at().plusSeconds(Math.round(gap * 60)), Kind.FREE, e.doctor(), null);
            }
            case REQUEUE -> {
                queue.requeue(desk, e.entry());
                if (!busy[e.doctor()]) {
                    busy[e.doctor()] = true;
                    schedule(e.at(), Kind.FREE, e.doctor(), null);
                }
            }
        }
    }

    private List<QueueStatus> entries(Doctor doctor) {
        return jdbc.queryForList("select status from queue_entries where queue_date = ? and doctor_id = ?",
                        String.class, clock.instant().atZone(CLINIC_ZONE).toLocalDate(), doctor.profile().getId())
                .stream().map(QueueStatus::valueOf).toList();
    }

    private void schedule(LocalDateTime at, Kind kind, int doctor, UUID entry) {
        events.add(new Event(at, order++, kind, doctor, entry));
    }

    private double exponential(double mean) {
        return -mean * Math.log(1 - random.nextDouble());
    }

    private double uniform(double from, double to) {
        return from + (to - from) * random.nextDouble();
    }

    private double logNormal(double mean, double sigma) {
        double mu = Math.log(mean) - sigma * sigma / 2;
        return Math.exp(mu + sigma * random.nextGaussian());
    }
}
