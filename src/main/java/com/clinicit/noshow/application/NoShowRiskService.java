package com.clinicit.noshow.application;

import com.clinicit.clinic.application.ClinicTime;
import com.clinicit.common.domain.InvalidRequestException;
import com.clinicit.identity.domain.Actor;
import com.clinicit.noshow.api.NoShowRiskEvaluation;
import com.clinicit.noshow.api.NoShowRiskResponse;
import com.clinicit.noshow.domain.NoShowRisk;
import com.clinicit.noshow.domain.NoShowRiskRule;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Advisory no-show flags ({@link NoShowRiskRule}) and their evaluation on the clinic's own
 * history.
 *
 * <p><b>No leakage.</b> Every judgement is made "as of" a moment: only appointments scheduled
 * before it, and only outcomes recorded before it (the {@code ARRIVED} and {@code NO_SHOW}
 * events' times), count. For the front desk that moment is now; in the evaluation it is the
 * start of the appointment's day, when reception would first see the day's list. The outcome
 * being predicted is never part of its own input.
 *
 * <p>An appointment is <i>kept</i> when the patient arrived, and <i>missed</i> when it was
 * marked no-show without the patient ever arriving. Walk-ins are left out (they are here by
 * definition), and so are cancellations and appointments without an outcome yet.
 *
 * <p>Each appointment's outcome times are read once from the history; the as-of rules are
 * then applied in memory by the same code for the front desk and for the evaluation.
 */
@Service
@Transactional(readOnly = true)
public class NoShowRiskService {

    static final int DEFAULT_EVALUATION_DAYS = 90;
    static final int MAX_EVALUATION_DAYS = 366;
    static final int CLINIC_RATE_DAYS = 180;

    /** One booked (not walk-in) appointment and when its outcome was recorded, if it was. */
    record Outcome(UUID id, UUID patientId, LocalDateTime scheduledAt, Instant arrivedAt, Instant missedAt) {
        boolean keptBy(Instant moment) {
            return arrivedAt != null && arrivedAt.isBefore(moment);
        }

        boolean missedBy(Instant moment) {
            return missedAt != null && missedAt.isBefore(moment);
        }

        boolean knownBy(Instant moment) {
            return keptBy(moment) || missedBy(moment);
        }
    }

    private static final String OUTCOMES = """
            select a.id, a.patient_id, a.scheduled_at,
                   min(e.occurred_at) filter (where e.event_type = 'ARRIVED')                                   as arrived_at,
                   min(e.occurred_at) filter (where e.event_type = 'NO_SHOW' and e.previous_status = 'CONFIRMED') as missed_at
            from appointments a
            left join operational_events e
                   on e.appointment_id = a.id and e.clinic_id = a.clinic_id and e.event_type in ('ARRIVED', 'NO_SHOW')
            where a.clinic_id = :clinic and not a.walk_in and a.scheduled_at < :before %s
            group by a.id, a.patient_id, a.scheduled_at
            """;

    private final NamedParameterJdbcTemplate jdbc;
    private final ClinicTime clinicTime;

    public NoShowRiskService(NamedParameterJdbcTemplate jdbc, ClinicTime clinicTime) {
        this.jdbc = jdbc;
        this.clinicTime = clinicTime;
    }

    /** Flags for a day's booked and confirmed appointments (not walk-ins), as of now. */
    public List<NoShowRiskResponse> forDay(Actor actor, LocalDate requestedDate) {
        UUID clinicId = actor.clinicId();
        LocalDate date = requestedDate != null ? requestedDate : clinicTime.today(clinicId);
        Map<UUID, UUID> targets = new java.util.LinkedHashMap<>();
        jdbc.query("""
                select id, patient_id from appointments
                where clinic_id = :clinic and scheduled_at >= :from and scheduled_at < :to
                  and status in ('BOOKED', 'CONFIRMED') and not walk_in
                order by scheduled_at, id
                """, new MapSqlParameterSource("clinic", clinicId)
                        .addValue("from", date.atStartOfDay()).addValue("to", date.plusDays(1).atStartOfDay()),
                rs -> {
                    targets.put(rs.getObject("id", UUID.class), rs.getObject("patient_id", UUID.class));
                });
        if (targets.isEmpty()) return List.of();

        Instant now = clinicTime.instant();
        LocalDateTime localNow = clinicTime.now(clinicId);
        Double clinicRate = clinicMissRate(
                outcomes(clinicId, localNow, localNow.minusDays(CLINIC_RATE_DAYS), null), now, localNow);
        Map<UUID, List<Outcome>> byPatient = byPatient(outcomes(clinicId, localNow, null, targets.values()));

        return targets.entrySet().stream()
                .map(target -> NoShowRiskResponse.of(target.getKey(),
                        assess(target.getKey(), byPatient.get(target.getValue()), now, localNow, clinicRate)))
                .toList();
    }

    /**
     * Replays the rule over past days (default: the 90 days before today), each day judged as
     * of its own start, and compares the flags with what then happened.
     */
    public NoShowRiskEvaluation evaluate(Actor actor, LocalDate requestedFrom, LocalDate requestedTo) {
        UUID clinicId = actor.clinicId();
        ZoneId zone = clinicTime.zone(clinicId);
        LocalDate to = requestedTo != null ? requestedTo : clinicTime.today(clinicId).minusDays(1);
        LocalDate from = requestedFrom != null ? requestedFrom : to.minusDays(DEFAULT_EVALUATION_DAYS - 1);
        if (from.isAfter(to)) throw new InvalidRequestException("from must not be after to");
        if (ChronoUnit.DAYS.between(from, to) + 1 > MAX_EVALUATION_DAYS) {
            throw new InvalidRequestException("The range can be at most " + MAX_EVALUATION_DAYS + " days");
        }

        // Every booked appointment up to the end of the range: the history each day may look back on.
        List<Outcome> all = outcomes(clinicId, to.plusDays(1).atStartOfDay(), null, null);
        Map<UUID, List<Outcome>> byPatient = byPatient(all);
        Map<LocalDate, List<Outcome>> byDay = all.stream()
                .filter(o -> !o.scheduledAt().toLocalDate().isBefore(from))
                .collect(Collectors.groupingBy(o -> o.scheduledAt().toLocalDate()));

        long judged = 0, unknown = 0, missed = 0, flagged = 0, flaggedMissed = 0, typical = 0, typicalMissed = 0;
        for (LocalDate day = from; !day.isAfter(to); day = day.plusDays(1)) {
            // Appointments of the day whose outcome is known today: the label, never an input.
            List<Outcome> labelled = byDay.getOrDefault(day, List.of()).stream()
                    .filter(o -> o.missedAt() != null || o.arrivedAt() != null)
                    .toList();
            if (labelled.isEmpty()) continue;

            // Judged with what was known at the start of that day.
            Instant asOf = day.atStartOfDay(zone).toInstant();
            LocalDateTime asOfLocal = day.atStartOfDay();
            LocalDateTime rateSince = asOfLocal.minusDays(CLINIC_RATE_DAYS);
            Double clinicRate = clinicMissRate(all.stream()
                    .filter(o -> !o.scheduledAt().isBefore(rateSince) && o.scheduledAt().isBefore(asOfLocal))
                    .toList(), asOf, asOfLocal);
            for (Outcome target : labelled) {
                NoShowRisk risk = assess(target.id(), byPatient.get(target.patientId()), asOf, asOfLocal, clinicRate);
                boolean wasMissed = target.missedAt() != null;
                if (wasMissed) missed++;
                switch (risk.level()) {
                    case UNKNOWN -> unknown++;
                    case ELEVATED -> {
                        judged++;
                        flagged++;
                        if (wasMissed) flaggedMissed++;
                    }
                    case TYPICAL -> {
                        judged++;
                        typical++;
                        if (wasMissed) typicalMissed++;
                    }
                }
            }
        }
        return NoShowRiskEvaluation.of(from, to, judged + unknown, missed, unknown, flagged, flaggedMissed,
                typical, typicalMissed);
    }

    /**
     * The rule for one appointment: the patient's last 10 earlier appointments whose outcome
     * was known at the moment (the appointment itself never counts).
     */
    private static NoShowRisk assess(UUID target, List<Outcome> patientHistory, Instant asOf, LocalDateTime asOfLocal,
                                     Double clinicRate) {
        List<Outcome> prior = (patientHistory == null ? List.<Outcome>of() : patientHistory).stream()
                .filter(o -> !o.id().equals(target) && o.scheduledAt().isBefore(asOfLocal) && o.knownBy(asOf))
                .sorted(Comparator.comparing(Outcome::scheduledAt).reversed())
                .limit(NoShowRiskRule.HISTORY_WINDOW)
                .toList();
        int missed = (int) prior.stream().filter(o -> o.missedBy(asOf)).count();
        return NoShowRiskRule.assess(prior.size(), missed, clinicRate);
    }

    /** Never-arrived rate among the given appointments' outcomes known at the moment; null without any. */
    private static Double clinicMissRate(List<Outcome> window, Instant asOf, LocalDateTime asOfLocal) {
        long known = 0, missed = 0;
        for (Outcome o : window) {
            if (!o.scheduledAt().isBefore(asOfLocal) || !o.knownBy(asOf)) continue;
            known++;
            if (o.missedBy(asOf)) missed++;
        }
        return known == 0 ? null : (double) missed / known;
    }

    private List<Outcome> outcomes(UUID clinicId, LocalDateTime before, LocalDateTime since, Collection<UUID> patients) {
        MapSqlParameterSource params = new MapSqlParameterSource("clinic", clinicId).addValue("before", before);
        List<String> filters = new ArrayList<>();
        if (since != null) {
            filters.add("and a.scheduled_at >= :since");
            params.addValue("since", since);
        }
        if (patients != null) {
            filters.add("and a.patient_id in (:patients)");
            params.addValue("patients", patients);
        }
        return jdbc.query(OUTCOMES.formatted(String.join(" ", filters)), params, (rs, i) -> new Outcome(
                rs.getObject("id", UUID.class),
                rs.getObject("patient_id", UUID.class),
                rs.getObject("scheduled_at", LocalDateTime.class),
                instant(rs.getTimestamp("arrived_at")),
                instant(rs.getTimestamp("missed_at"))));
    }

    private static Map<UUID, List<Outcome>> byPatient(List<Outcome> outcomes) {
        return outcomes.stream().collect(Collectors.groupingBy(Outcome::patientId));
    }

    private static Instant instant(Timestamp timestamp) {
        return timestamp == null ? null : timestamp.toInstant();
    }
}
