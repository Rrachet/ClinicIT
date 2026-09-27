package com.clinicit.notification.api;

import com.clinicit.notification.domain.Notification;
import com.clinicit.notification.domain.NotificationChannel;
import com.clinicit.notification.domain.NotificationStatus;
import com.clinicit.notification.domain.NotificationType;

import java.time.Instant;
import java.util.UUID;

/**
 * What front-desk staff see about a message: enough to answer "did the patient get their
 * link?". The recipient is masked; the body is the fixed, non-clinical template text.
 */
public record NotificationResponse(
        UUID id,
        UUID appointmentId,
        NotificationType type,
        NotificationChannel channel,
        String recipient,
        String body,
        NotificationStatus status,
        int attempts,
        int maxAttempts,
        String lastError,
        Instant createdAt,
        Instant sentAt,
        Instant nextAttemptAt,
        Instant expiresAt
) {
    public static NotificationResponse from(Notification n) {
        return new NotificationResponse(
                n.getId(), n.getAppointmentId(), n.getType(), n.getChannel(), mask(n.getRecipient()), n.getBody(),
                n.getStatus(), n.getAttempts(), n.getMaxAttempts(), n.getLastError(), n.getCreatedAt(), n.getSentAt(),
                n.getStatus() == NotificationStatus.PENDING ? n.getNextAttemptAt() : null, n.getExpiresAt());
    }

    /** "+91 98765 43210" → "•••3210". */
    static String mask(String recipient) {
        String digits = recipient.replaceAll("\\D", "");
        String tail = digits.length() >= 4 ? digits.substring(digits.length() - 4) : "";
        return "•••" + tail;
    }
}
