package com.clinicit.notification.domain;

import jakarta.persistence.*;

import java.time.Instant;
import java.util.UUID;

/**
 * One message to one patient, and its delivery state. Rows are created by
 * NotificationService (in the causing transaction) and updated by NotificationDispatcher.
 * Mapped for reads; writes use atomic SQL so concurrent senders cannot double-send.
 */
@Entity
@Table(name = "notifications")
public class Notification {

    @Id
    private UUID id;

    @Column(name = "clinic_id", nullable = false, updatable = false)
    private UUID clinicId;

    @Column(name = "appointment_id", nullable = false, updatable = false)
    private UUID appointmentId;

    @Column(name = "queue_entry_id", updatable = false)
    private UUID queueEntryId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 40, updatable = false)
    private NotificationType type;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16, updatable = false)
    private NotificationChannel channel;

    @Column(nullable = false, length = 254, updatable = false)
    private String recipient;

    @Column(nullable = false, length = 640, updatable = false)
    private String body;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private NotificationStatus status;

    @Column(name = "dedupe_key", nullable = false, length = 200, updatable = false)
    private String dedupeKey;

    @Column(nullable = false)
    private int attempts;

    @Column(name = "max_attempts", nullable = false)
    private int maxAttempts;

    @Column(name = "next_attempt_at", nullable = false)
    private Instant nextAttemptAt;

    @Column(name = "expires_at", nullable = false, updatable = false)
    private Instant expiresAt;

    @Column(name = "last_error", length = 300)
    private String lastError;

    @Column(length = 40)
    private String provider;

    @Column(name = "provider_message_id", length = 120)
    private String providerMessageId;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "sent_at")
    private Instant sentAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected Notification() {
        // for JPA
    }

    public UUID getId() { return id; }
    public UUID getClinicId() { return clinicId; }
    public UUID getAppointmentId() { return appointmentId; }
    public UUID getQueueEntryId() { return queueEntryId; }
    public NotificationType getType() { return type; }
    public NotificationChannel getChannel() { return channel; }
    public String getRecipient() { return recipient; }
    public String getBody() { return body; }
    public NotificationStatus getStatus() { return status; }
    public String getDedupeKey() { return dedupeKey; }
    public int getAttempts() { return attempts; }
    public int getMaxAttempts() { return maxAttempts; }
    public Instant getNextAttemptAt() { return nextAttemptAt; }
    public Instant getExpiresAt() { return expiresAt; }
    public String getLastError() { return lastError; }
    public String getProvider() { return provider; }
    public String getProviderMessageId() { return providerMessageId; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getSentAt() { return sentAt; }
}
