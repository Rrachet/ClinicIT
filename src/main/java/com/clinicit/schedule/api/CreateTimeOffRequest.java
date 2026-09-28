package com.clinicit.schedule.api;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.time.LocalDateTime;

/** Clinic-local times, end exclusive. A whole day off is 00:00 to 00:00 the next day. */
public record CreateTimeOffRequest(
        @NotNull LocalDateTime startsAt,
        @NotNull LocalDateTime endsAt,
        @Size(max = 200) String reason
) {}
