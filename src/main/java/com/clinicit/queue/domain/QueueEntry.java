package com.clinicit.queue.domain;

import com.clinicit.appointment.domain.Appointment;
import com.clinicit.common.domain.InvalidStateTransitionException;
import jakarta.persistence.*;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

@Entity
@Table(
        name = "queue_entries",
        uniqueConstraints = @UniqueConstraint(
                name = "uk_queue_clinic_date_token",
                columnNames = {"clinic_id", "queue_date", "token_number"}
        )
)
public class QueueEntry {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "appointment_id", nullable = false, unique = true, updatable = false)
    private UUID appointmentId;

    @Column(name = "clinic_id", nullable = false, updatable = false)
    private UUID clinicId;

    @Column(name = "doctor_id", nullable = false, updatable = false)
    private UUID doctorId;

    @Column(name = "queue_date", nullable = false, updatable = false)
    private LocalDate queueDate;

    @Column(name = "token_number", nullable = false, updatable = false)
    private Integer tokenNumber;

    /** Unguessable code for the patient's public status page (see PublicQueueStatusService). */
    @Column(name = "status_code", nullable = false, updatable = false, length = 32)
    private String statusCode;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 32)
    private QueueStatus status;

    @Column(name = "checked_in_at")
    private Instant checkedInAt;

    @Column(name = "called_at")
    private Instant calledAt;

    @Column(name = "consultation_started_at")
    private Instant consultationStartedAt;

    @Column(name = "completed_at")
    private Instant completedAt;

    @Column(name = "skipped_at")
    private Instant skippedAt;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    // Optimistic lock: a stale copy of the entry can never overwrite a newer state.
    @Version
    @Column(nullable = false)
    private long version;

    protected QueueEntry() {
        // for JPA
    }

    /** Creates the entry for an appointment that has just joined the queue with the given token. */
    public static QueueEntry join(Appointment appointment, LocalDate queueDate, int tokenNumber, Instant now) {
        QueueEntry entry = new QueueEntry();
        entry.appointmentId = appointment.getId();
        entry.clinicId = appointment.getClinicId();
        entry.doctorId = appointment.getDoctorId();
        entry.queueDate = queueDate;
        entry.tokenNumber = tokenNumber;
        entry.statusCode = StatusCodes.next();
        entry.status = QueueStatus.WAITING;
        entry.checkedInAt = now;
        entry.createdAt = now;
        entry.updatedAt = now;
        return entry;
    }

    public void call(Instant now) {
        transitionTo(QueueStatus.CALLED, now);
        calledAt = now;
    }

    public void startConsultation(Instant now) {
        transitionTo(QueueStatus.IN_CONSULTATION, now);
        consultationStartedAt = now;
    }

    public void complete(Instant now) {
        transitionTo(QueueStatus.COMPLETED, now);
        completedAt = now;
    }

    public void skip(Instant now) {
        transitionTo(QueueStatus.SKIPPED, now);
        skippedAt = now;
    }

    /** A skipped patient came back; they keep their token and so their place in line. */
    public void requeue(Instant now) {
        transitionTo(QueueStatus.WAITING, now);
        calledAt = null;
    }

    public void markNoShow(Instant now) {
        transitionTo(QueueStatus.NO_SHOW, now);
    }

    private void transitionTo(QueueStatus target, Instant now) {
        if (!status.canTransitionTo(target)) {
            throw new InvalidStateTransitionException("queue entry", status, target);
        }
        status = target;
        updatedAt = now;
    }

    public UUID getId() { return id; }
    public UUID getAppointmentId() { return appointmentId; }
    public UUID getClinicId() { return clinicId; }
    public UUID getDoctorId() { return doctorId; }
    public LocalDate getQueueDate() { return queueDate; }
    public Integer getTokenNumber() { return tokenNumber; }
    public String getStatusCode() { return statusCode; }
    public QueueStatus getStatus() { return status; }
    public Instant getCheckedInAt() { return checkedInAt; }
    public Instant getCalledAt() { return calledAt; }
    public Instant getConsultationStartedAt() { return consultationStartedAt; }
    public Instant getCompletedAt() { return completedAt; }
    public Instant getSkippedAt() { return skippedAt; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
    public long getVersion() { return version; }
}
