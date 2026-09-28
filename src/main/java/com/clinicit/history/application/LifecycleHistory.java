package com.clinicit.history.application;

import com.clinicit.appointment.application.AppointmentMetrics;
import com.clinicit.appointment.domain.Appointment;
import com.clinicit.appointment.domain.AppointmentStatus;
import com.clinicit.history.domain.OperationalEventType;
import com.clinicit.identity.domain.Actor;
import com.clinicit.queue.domain.QueueEntry;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.UUID;

/**
 * Appends to the immutable operational history ({@code operational_events}).
 *
 * <p>Called directly by the appointment and queue services, inside the transaction of the
 * transition itself ({@code MANDATORY}): the event exists if and only if the transition
 * committed. Unlike notifications, a failure here is not swallowed. A transition that
 * cannot be recorded does not happen, because history that silently misses events would
 * make every analytic built on it wrong.
 *
 * <p>Append-only: this class only inserts, and a database trigger rejects any UPDATE or
 * DELETE of an event.
 */
@Component
public class LifecycleHistory {

    private static final String INSERT = """
            insert into operational_events (id, clinic_id, appointment_id, queue_entry_id, doctor_id, patient_id,
                                            event_type, previous_status, occurred_at, actor_user_id,
                                            token_number, scheduled_at)
            values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            """;

    private final JdbcTemplate jdbc;
    private final AppointmentMetrics metrics;

    @PersistenceContext
    private EntityManager entityManager;

    public LifecycleHistory(JdbcTemplate jdbc, AppointmentMetrics metrics) {
        this.jdbc = jdbc;
        this.metrics = metrics;
    }

    /** A new appointment. Records its scheduled time, which later gives the appointment delay. */
    @Transactional(propagation = Propagation.MANDATORY)
    public void booked(Appointment appointment, Actor actor, Instant at) {
        insert(appointment, null, OperationalEventType.BOOKED, null, actor, at);
        metrics.reached(AppointmentStatus.BOOKED);
    }

    /** CONFIRMED, ARRIVED, CANCELLED or NO_SHOW before the patient reached the queue. */
    @Transactional(propagation = Propagation.MANDATORY)
    public void appointmentChanged(Appointment appointment, AppointmentStatus previous, Actor actor, Instant at) {
        insert(appointment, null, OperationalEventType.ofTransition(previous, appointment.getStatus()),
                previous, actor, at);
        metrics.reached(appointment.getStatus());
    }

    /** A new time for a booked or confirmed appointment. Records the new time; the status is unchanged. */
    @Transactional(propagation = Propagation.MANDATORY)
    public void rescheduled(Appointment appointment, Actor actor, Instant at) {
        insert(appointment, null, OperationalEventType.RESCHEDULED, appointment.getStatus(), actor, at);
    }

    /** Joined the queue, or any later queue transition (the appointment mirrors the entry). */
    @Transactional(propagation = Propagation.MANDATORY)
    public void queueChanged(
            Appointment appointment, QueueEntry entry, AppointmentStatus previous, Actor actor, Instant at
    ) {
        insert(appointment, entry, OperationalEventType.ofTransition(previous, appointment.getStatus()),
                previous, actor, at);
        if (appointment.getStatus() != previous) metrics.reached(appointment.getStatus());
    }

    private void insert(
            Appointment appointment, QueueEntry entry, OperationalEventType type,
            AppointmentStatus previous, Actor actor, Instant at
    ) {
        // The appointment or queue entry may not be written yet; the foreign keys need them.
        entityManager.flush();
        jdbc.update(INSERT,
                UUID.randomUUID(),
                appointment.getClinicId(),
                appointment.getId(),
                entry == null ? null : entry.getId(),
                appointment.getDoctorId(),
                appointment.getPatientId(),
                type.name(),
                previous == null ? null : previous.name(),
                Timestamp.from(at),
                actor == null ? null : actor.userId(),
                entry == null ? null : entry.getTokenNumber(),
                type == OperationalEventType.BOOKED || type == OperationalEventType.RESCHEDULED
                        ? appointment.getScheduledAt() : null);
    }
}
