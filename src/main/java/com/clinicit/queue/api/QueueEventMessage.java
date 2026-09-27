package com.clinicit.queue.api;

import com.clinicit.queue.domain.QueueEventRecord;
import com.clinicit.queue.domain.QueueEventType;
import com.clinicit.queue.domain.QueueStatus;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

/**
 * The real-time contract (see docs/REALTIME.md). A notification that one queue entry
 * changed, with its new state. It contains identifiers and queue state only: no patient
 * name, phone, date of birth or reason for visit. Staff screens that show names look
 * them up via the authenticated REST API.
 *
 * @param eventId       unique; delivery is at-least-once, so clients dedupe on this
 * @param sequence      database sequence of the event; increases per queue entry
 * @param entryVersion  version of the queue entry after this change; a client should ignore
 *                      an event whose entryVersion is not greater than the one it already has
 */
public record QueueEventMessage(
        UUID eventId,
        long sequence,
        QueueEventType type,
        Instant occurredAt,
        UUID clinicId,
        UUID doctorId,
        LocalDate queueDate,
        UUID queueEntryId,
        UUID appointmentId,
        int tokenNumber,
        QueueStatus status,
        QueueStatus previousStatus,
        long entryVersion
) {
    public static QueueEventMessage from(QueueEventRecord event) {
        return new QueueEventMessage(
                event.getEventId(),
                event.getId(),
                event.getType(),
                event.getOccurredAt(),
                event.getClinicId(),
                event.getDoctorId(),
                event.getQueueDate(),
                event.getQueueEntryId(),
                event.getAppointmentId(),
                event.getTokenNumber(),
                event.getStatus(),
                event.getPreviousStatus(),
                event.getEntryVersion()
        );
    }
}
