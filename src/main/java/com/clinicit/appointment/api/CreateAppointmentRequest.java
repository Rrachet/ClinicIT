package com.clinicit.appointment.api;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.time.LocalDateTime;
import java.util.UUID;

public record CreateAppointmentRequest(
        @NotNull UUID clinicId,
        @NotNull UUID patientId,
        @NotNull UUID doctorId,
        @NotNull LocalDateTime scheduledAt,
        @Size(max = 500) String reasonSummary
) {}
