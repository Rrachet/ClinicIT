package com.clinicit.queue.domain;

import jakarta.persistence.*;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

/**
 * Outbox row: one committed queue state change, waiting to be (or already) pushed to
 * real-time subscribers. Deliberately carries no patient data.
 */
@Entity
@Table(name = "queue_events")
public class QueueEventRecord {

    /** Database sequence: monotonic per queue entry, a rough global order otherwise. */
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "event_id", nullable = false, updatable = false)
    private UUID eventId;

    @Column(name = "clinic_id", nullable = false, updatable = false)
    private UUID clinicId;

    @Column(name = "doctor_id", nullable = false, updatable = false)
    private UUID doctorId;

    @Column(name = "queue_entry_id", nullable = false, updatable = false)
    private UUID queueEntryId;

    @Column(name = "appointment_id", nullable = false, updatable = false)
    private UUID appointmentId;

    @Column(name = "queue_date", nullable = false, updatable = false)
    private LocalDate queueDate;

    @Column(name = "token_number", nullable = false, updatable = false)
    private Integer tokenNumber;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 40, updatable = false)
    private QueueEventType type;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 32, updatable = false)
    private QueueStatus status;

    @Enumerated(EnumType.STRING)
    @Column(name = "previous_status", length = 32, updatable = false)
    private QueueStatus previousStatus;

    @Column(name = "entry_version", nullable = false, updatable = false)
    private long entryVersion;

    @Column(name = "occurred_at", nullable = false, updatable = false)
    private Instant occurredAt;

    @Column(name = "published_at")
    private Instant publishedAt;

    protected QueueEventRecord() {
        // for JPA
    }

    public static QueueEventRecord of(QueueEntry entry, QueueStatus previousStatus, Instant occurredAt) {
        QueueEventRecord event = new QueueEventRecord();
        event.eventId = UUID.randomUUID();
        event.clinicId = entry.getClinicId();
        event.doctorId = entry.getDoctorId();
        event.queueEntryId = entry.getId();
        event.appointmentId = entry.getAppointmentId();
        event.queueDate = entry.getQueueDate();
        event.tokenNumber = entry.getTokenNumber();
        event.status = entry.getStatus();
        event.previousStatus = previousStatus;
        event.type = QueueEventType.of(previousStatus, entry.getStatus());
        event.entryVersion = entry.getVersion();
        event.occurredAt = occurredAt;
        return event;
    }

    public Long getId() { return id; }
    public UUID getEventId() { return eventId; }
    public UUID getClinicId() { return clinicId; }
    public UUID getDoctorId() { return doctorId; }
    public UUID getQueueEntryId() { return queueEntryId; }
    public UUID getAppointmentId() { return appointmentId; }
    public LocalDate getQueueDate() { return queueDate; }
    public Integer getTokenNumber() { return tokenNumber; }
    public QueueEventType getType() { return type; }
    public QueueStatus getStatus() { return status; }
    public QueueStatus getPreviousStatus() { return previousStatus; }
    public long getEntryVersion() { return entryVersion; }
    public Instant getOccurredAt() { return occurredAt; }
    public Instant getPublishedAt() { return publishedAt; }
}
