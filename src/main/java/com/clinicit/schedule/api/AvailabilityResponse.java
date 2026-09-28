package com.clinicit.schedule.api;

import com.clinicit.schedule.domain.WorkingHours;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.List;
import java.util.UUID;

/**
 * One doctor's day for booking. {@code scheduled=false}: no weekly hours are set, so any time
 * can be booked and {@code slots} is empty.
 *
 * @param slots every slot of the day; {@code reason} (DAY_OFF, ON_BREAK, TIME_OFF,
 *              IN_THE_PAST, SLOT_TAKEN, ...) when it is not available
 */
public record AvailabilityResponse(
        UUID doctorId,
        LocalDate date,
        boolean scheduled,
        int appointmentMinutes,
        Hours hours,
        List<Period> timeOff,
        List<Slot> slots
) {
    public record Hours(LocalTime start, LocalTime end, LocalTime breakStart, LocalTime breakEnd) {
        public static Hours from(WorkingHours day) {
            return new Hours(day.start(), day.end(), day.breakStart(), day.breakEnd());
        }
    }

    public record Period(LocalDateTime startsAt, LocalDateTime endsAt) {}

    public record Slot(LocalDateTime start, LocalDateTime end, boolean available, String reason) {}
}
