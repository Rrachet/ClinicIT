package com.clinicit.history.domain;

import java.time.Instant;
import java.time.LocalDateTime;
import java.util.UUID;

/**
 * One immutable entry of the operational history. Identifiers, times and a little
 * structured metadata only: no names, phone numbers or clinical text.
 *
 * @param queueEntryId   set once the appointment is in the queue
 * @param previousStatus appointment status before the transition; null for BOOKED
 * @param actorUserId    staff user who caused it; null for system actions
 * @param tokenNumber    queue token, for queue events
 * @param scheduledAt    clinic-local appointment time, recorded on BOOKED
 */
public record OperationalEvent(
        UUID id,
        long seq,
        UUID clinicId,
        UUID appointmentId,
        UUID queueEntryId,
        UUID doctorId,
        UUID patientId,
        OperationalEventType type,
        String previousStatus,
        Instant occurredAt,
        UUID actorUserId,
        Integer tokenNumber,
        LocalDateTime scheduledAt
) {
}
