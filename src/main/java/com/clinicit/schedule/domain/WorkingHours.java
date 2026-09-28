package com.clinicit.schedule.domain;

import com.clinicit.common.domain.InvalidRequestException;

import java.time.DayOfWeek;
import java.time.LocalDateTime;
import java.time.LocalTime;

/**
 * A doctor's hours on one day of the week, with at most one break. Clinic-local wall-clock
 * times (docs/SCHEDULING.md).
 */
public record WorkingHours(DayOfWeek dayOfWeek, LocalTime start, LocalTime end, LocalTime breakStart, LocalTime breakEnd) {

    public WorkingHours {
        if (dayOfWeek == null || start == null || end == null) {
            throw new InvalidRequestException("INVALID_SCHEDULE", "Each working day needs a day, a start and an end");
        }
        if (!start.isBefore(end)) {
            throw new InvalidRequestException("INVALID_SCHEDULE", dayOfWeek + ": the start must be before the end");
        }
        if ((breakStart == null) != (breakEnd == null)) {
            throw new InvalidRequestException("INVALID_SCHEDULE", dayOfWeek + ": a break needs both a start and an end");
        }
        if (breakStart != null && !(start.isBefore(breakStart) && breakStart.isBefore(breakEnd) && breakEnd.isBefore(end))) {
            throw new InvalidRequestException("INVALID_SCHEDULE", dayOfWeek + ": the break must lie inside the working hours");
        }
    }

    public boolean hasBreak() {
        return breakStart != null;
    }

    /** True when [from, to) on this day overlaps the break. */
    boolean overlapsBreak(LocalTime from, LocalTime to) {
        return hasBreak() && from.isBefore(breakEnd) && breakStart.isBefore(to);
    }

    LocalDateTime startOn(java.time.LocalDate date) {
        return date.atTime(start);
    }

    LocalDateTime endOn(java.time.LocalDate date) {
        return date.atTime(end);
    }
}
