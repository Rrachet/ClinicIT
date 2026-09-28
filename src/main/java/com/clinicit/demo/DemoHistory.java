package com.clinicit.demo;

import org.springframework.jdbc.core.JdbcTemplate;

import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Random;
import java.util.UUID;

/**
 * Finished demo visits written straight into the tables, with the timestamps they would have
 * had: the services record everything at "now", and history needs the past. Every row obeys
 * the same constraints as live data (foreign keys, unique tokens, the append-only history),
 * and events are inserted in time order so their sequence numbers are chronological.
 *
 * <p>Used only by {@link DemoData}, never with the prod profile.
 */
final class DemoHistory {

    record Doctor(UUID id, int meanMinutes) {}

    private record Event(Instant at, UUID appointmentId, UUID queueEntryId, UUID doctorId, UUID patientId,
                         String type, String previous, Integer token, LocalDateTime scheduledAt) {}

    private final JdbcTemplate jdbc;
    private final UUID clinicId;
    private final UUID actorId;
    private final ZoneId zone;
    private final Random random;
    private final List<Event> events = new ArrayList<>();

    DemoHistory(JdbcTemplate jdbc, UUID clinicId, UUID actorId, ZoneId zone, long seed) {
        this.jdbc = jdbc;
        this.clinicId = clinicId;
        this.actorId = actorId;
        this.zone = zone;
        this.random = new Random(seed);
    }

    /**
     * A full clinic day: each doctor sees patients from 09:00 in check-in order, with a few
     * walk-ins, no-shows and cancellations. Returns the last token issued that day.
     */
    int day(LocalDate date, List<Doctor> doctors, List<UUID> patients, LocalDateTime notAfter) {
        record Visit(Doctor doctor, UUID patient, LocalDateTime scheduled, LocalDateTime checkIn, boolean walkIn) {}
        List<Visit> arriving = new ArrayList<>();
        for (Doctor doctor : doctors) {
            int count = 6 + random.nextInt(5);
            LocalDateTime slot = date.atTime(9, 0);
            for (int i = 0; i < count; i++, slot = slot.plusMinutes(doctor.meanMinutes())) {
                UUID patient = patients.get(random.nextInt(patients.size()));
                double roll = random.nextDouble();
                if (roll < 0.07) {
                    cancelled(doctor, patient, slot, notAfter);
                } else if (roll < 0.15) {
                    noShow(doctor, patient, slot, notAfter);
                } else {
                    boolean walkIn = roll > 0.9;
                    LocalDateTime checkIn = slot.minusMinutes(random.nextInt(12)).plusMinutes(walkIn ? random.nextInt(20) : 0);
                    arriving.add(new Visit(doctor, patient, walkIn ? checkIn : slot, checkIn, walkIn));
                }
            }
        }
        arriving.sort(Comparator.comparing(Visit::checkIn));

        int token = 0;
        java.util.Map<UUID, LocalDateTime> doctorFree = new java.util.HashMap<>();
        for (Visit visit : arriving) {
            LocalDateTime free = doctorFree.getOrDefault(visit.doctor().id(), date.atTime(9, 0));
            LocalDateTime called = visit.checkIn().isAfter(free) ? visit.checkIn() : free;
            LocalDateTime started = called.plusMinutes(1);
            long minutes = Math.max(3, Math.round(visit.doctor().meanMinutes() * (0.7 + 0.6 * random.nextDouble())));
            LocalDateTime completed = started.plusMinutes(minutes);
            if (completed.isAfter(notAfter)) continue; // not finished yet: today's live queue is seeded separately
            doctorFree.put(visit.doctor().id(), completed.plusMinutes(1));
            completedVisit(visit.doctor(), visit.patient(), date, ++token, visit.scheduled(), visit.checkIn(),
                    called, started, completed, visit.walkIn());
        }
        return token;
    }

    private void completedVisit(Doctor doctor, UUID patient, LocalDate date, int token, LocalDateTime scheduled,
                                LocalDateTime checkIn, LocalDateTime called, LocalDateTime started,
                                LocalDateTime completed, boolean walkIn) {
        UUID appointment = appointment(doctor, patient, scheduled, "COMPLETED", walkIn,
                walkIn ? checkIn : scheduled.minusDays(1).withHour(18));
        UUID entry = UUID.randomUUID();
        jdbc.update("""
                insert into queue_entries (id, appointment_id, clinic_id, doctor_id, queue_date, token_number, status,
                                           checked_in_at, called_at, consultation_started_at, completed_at,
                                           created_at, updated_at, version, status_code)
                values (?, ?, ?, ?, ?, ?, 'COMPLETED', ?, ?, ?, ?, ?, ?, 4, ?)
                """, entry, appointment, clinicId, doctor.id(), date, token, ts(checkIn), ts(called), ts(started),
                ts(completed), ts(checkIn), ts(completed), UUID.randomUUID().toString().replace("-", ""));
        LocalDateTime booked = walkIn ? checkIn : scheduled.minusDays(1).withHour(18);
        event(booked, appointment, null, doctor, patient, "BOOKED", null, null, scheduled);
        event(walkIn ? checkIn : scheduled.minusHours(2), appointment, null, doctor, patient, "CONFIRMED", "BOOKED", null, null);
        event(checkIn, appointment, null, doctor, patient, "ARRIVED", "CONFIRMED", null, null);
        event(checkIn.plusSeconds(20), appointment, entry, doctor, patient, "WAITING", "ARRIVED", token, null);
        event(called, appointment, entry, doctor, patient, "CALLED", "WAITING", token, null);
        event(started, appointment, entry, doctor, patient, "IN_CONSULTATION", "CALLED", token, null);
        event(completed, appointment, entry, doctor, patient, "COMPLETED", "IN_CONSULTATION", token, null);
    }

    private void noShow(Doctor doctor, UUID patient, LocalDateTime slot, LocalDateTime notAfter) {
        LocalDateTime recorded = slot.plusMinutes(45);
        if (recorded.isAfter(notAfter)) return;
        LocalDateTime booked = slot.minusDays(2).withHour(17);
        UUID appointment = appointment(doctor, patient, slot, "NO_SHOW", false, booked);
        event(booked, appointment, null, doctor, patient, "BOOKED", null, null, slot);
        event(slot.minusHours(3), appointment, null, doctor, patient, "CONFIRMED", "BOOKED", null, null);
        event(recorded, appointment, null, doctor, patient, "NO_SHOW", "CONFIRMED", null, null);
    }

    private void cancelled(Doctor doctor, UUID patient, LocalDateTime slot, LocalDateTime notAfter) {
        LocalDateTime cancelledAt = slot.minusHours(5);
        if (cancelledAt.isAfter(notAfter)) return;
        LocalDateTime booked = slot.minusDays(3).withHour(11);
        UUID appointment = appointment(doctor, patient, slot, "CANCELLED", false, booked);
        event(booked, appointment, null, doctor, patient, "BOOKED", null, null, slot);
        event(cancelledAt, appointment, null, doctor, patient, "CANCELLED", "BOOKED", null, null);
    }

    private UUID appointment(Doctor doctor, UUID patient, LocalDateTime scheduled, String status, boolean walkIn,
                             LocalDateTime created) {
        UUID id = UUID.randomUUID();
        jdbc.update("""
                insert into appointments (id, clinic_id, patient_id, doctor_id, scheduled_at, status, walk_in,
                                          created_at, updated_at)
                values (?, ?, ?, ?, ?, ?, ?, ?, ?)
                """, id, clinicId, patient, doctor.id(), scheduled, status, walkIn, ts(created), ts(created));
        return id;
    }

    private void event(LocalDateTime at, UUID appointment, UUID entry, Doctor doctor, UUID patient, String type,
                       String previous, Integer token, LocalDateTime scheduledAt) {
        events.add(new Event(at.atZone(zone).toInstant(), appointment, entry, doctor.id(), patient, type, previous,
                token, scheduledAt));
    }

    /** Writes the collected history in time order, so sequence numbers follow time. */
    void flushEvents() {
        events.sort(Comparator.comparing(Event::at));
        for (Event e : events) {
            jdbc.update("""
                    insert into operational_events (id, clinic_id, appointment_id, queue_entry_id, doctor_id, patient_id,
                                                    event_type, previous_status, occurred_at, actor_user_id,
                                                    token_number, scheduled_at)
                    values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                    """, UUID.randomUUID(), clinicId, e.appointmentId(), e.queueEntryId(), e.doctorId(), e.patientId(),
                    e.type(), e.previous(), Timestamp.from(e.at()), actorId, e.token(), e.scheduledAt());
        }
        events.clear();
    }

    void setLastToken(LocalDate date, int lastToken) {
        if (lastToken <= 0) return;
        jdbc.update("""
                insert into queue_token_counters (clinic_id, queue_date, last_token) values (?, ?, ?)
                on conflict (clinic_id, queue_date) do update set last_token = excluded.last_token
                """, clinicId, date, lastToken);
    }

    private Timestamp ts(LocalDateTime local) {
        return Timestamp.from(local.atZone(zone).toInstant());
    }
}
