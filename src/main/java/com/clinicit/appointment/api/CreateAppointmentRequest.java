package com.clinicit.appointment.api;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * Booked in the caller's clinic; patient and doctor must belong to it.
 *
 * @param scheduledAt clinic-local wall-clock time; required unless {@code walkIn}
 * @param walkIn      the patient is at the desk now: booked for the current time, never
 *                    holding a slot (docs/SCHEDULING.md); {@code scheduledAt} is ignored
 */
public record CreateAppointmentRequest(
        @NotNull UUID patientId,
        @NotNull UUID doctorId,
        LocalDateTime scheduledAt,
        @Size(max = 500) String reasonSummary,
        Boolean walkIn
) {
    public CreateAppointmentRequest(UUID patientId, UUID doctorId, LocalDateTime scheduledAt, String reasonSummary) {
        this(patientId, doctorId, scheduledAt, reasonSummary, false);
    }

    public boolean isWalkIn() {
        return Boolean.TRUE.equals(walkIn);
    }
}
