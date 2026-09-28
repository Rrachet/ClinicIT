package com.clinicit.appointment.api;

import jakarta.validation.constraints.NotNull;

import java.time.LocalDateTime;

/** A new clinic-local time with the same doctor. */
public record RescheduleAppointmentRequest(@NotNull LocalDateTime scheduledAt) {}
