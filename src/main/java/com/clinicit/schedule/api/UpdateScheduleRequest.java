package com.clinicit.schedule.api;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import com.clinicit.schedule.api.ScheduleResponse.Day;

import java.util.List;

/** Replaces the whole week. An empty list removes the schedule (no rules enforced). */
public record UpdateScheduleRequest(
        @NotNull @Min(5) @Max(120) Integer appointmentMinutes,
        @NotNull @Size(max = 7) List<@Valid @NotNull Day> weeklyHours
) {}
