package com.clinicit.appointment.api;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * Booked in the caller's clinic; patient and doctor must belong to it.
 *
 * @param scheduledAt clinic-local wall-clock time
 */
public record CreateAppointmentRequest(
        @NotNull UUID patientId,
        @NotNull UUID doctorId,
        @NotNull LocalDateTime scheduledAt,
        @Size(max = 500) String reasonSummary
) {}
