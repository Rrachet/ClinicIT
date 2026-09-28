package com.clinicit.schedule.domain;

import java.time.DayOfWeek;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * One doctor's weekly hours, slot length and time off: the rules for when they can be
 * booked. Pure (no clock, no database), so every rule is unit tested directly.
 *
 * <p>A doctor with no working hours at all has <b>no schedule yet</b>: nothing is enforced,
 * exactly as before scheduling existed, so a clinic can adopt schedules one doctor at a time.
 */
public final class DoctorSchedule {

    private final UUID doctorId;
    private final int appointmentMinutes;
    private final Map<DayOfWeek, WorkingHours> week;
    private final List<TimeOff> timeOff;

    public DoctorSchedule(UUID doctorId, int appointmentMinutes, List<WorkingHours> hours, List<TimeOff> timeOff) {
        this.doctorId = doctorId;
        this.appointmentMinutes = appointmentMinutes;
        Map<DayOfWeek, WorkingHours> byDay = new EnumMap<>(DayOfWeek.class);
        for (WorkingHours day : hours) byDay.put(day.dayOfWeek(), day);
        this.week = Collections.unmodifiableMap(byDay);
        this.timeOff = List.copyOf(timeOff);
    }

    public UUID doctorId() { return doctorId; }
    public int appointmentMinutes() { return appointmentMinutes; }
    public Duration appointmentLength() { return Duration.ofMinutes(appointmentMinutes); }
    public List<TimeOff> timeOff() { return timeOff; }

    public boolean isConfigured() {
        return !week.isEmpty();
    }

    public Optional<WorkingHours> hoursOn(LocalDate date) {
        return Optional.ofNullable(week.get(date.getDayOfWeek()));
    }

    public List<WorkingHours> weeklyHours() {
        return List.copyOf(week.values());
    }

    /**
     * Why a booked appointment starting at {@code start} (lasting one slot) is not possible,
     * ignoring other appointments. Empty when the time is fine, or when no schedule is set.
     */
    public Optional<Unavailable> checkSlot(LocalDateTime start, LocalDateTime now) {
        if (!isConfigured()) return Optional.empty();
        if (start.isBefore(now)) return Optional.of(Unavailable.IN_THE_PAST);
        WorkingHours day = week.get(start.getDayOfWeek());
        if (day == null) return Optional.of(Unavailable.DAY_OFF);
        LocalDateTime end = start.plus(appointmentLength());
        // The whole slot must fit in the day's hours (a slot never runs past midnight).
        if (start.isBefore(day.startOn(start.toLocalDate())) || end.isAfter(day.endOn(start.toLocalDate()))) {
            return Optional.of(Unavailable.OUTSIDE_HOURS);
        }
        if (day.overlapsBreak(start.toLocalTime(), end.toLocalTime())) return Optional.of(Unavailable.ON_BREAK);
        if (timeOff.stream().anyMatch(off -> off.overlaps(start, end))) return Optional.of(Unavailable.TIME_OFF);
        return Optional.empty();
    }

    /**
     * Whether a walk-in arriving at {@code now} can still be seen today. Walk-ins may arrive
     * early, during the break or while the doctor is briefly away: they simply wait in the
     * queue. They are refused only when the doctor will not work again today.
     */
    public Optional<Unavailable> checkWalkIn(LocalDateTime now) {
        if (!isConfigured()) return Optional.empty();
        WorkingHours day = week.get(now.getDayOfWeek());
        if (day == null) return Optional.of(Unavailable.DAY_OFF);
        LocalDateTime dayEnd = day.endOn(now.toLocalDate());
        if (!now.isBefore(dayEnd)) return Optional.of(Unavailable.NOT_WORKING_TODAY);
        // Walk forward from now (or the day's start) through time off; if it reaches the end
        // of the day, the doctor is away for the rest of it.
        LocalDateTime from = now.isAfter(day.startOn(now.toLocalDate())) ? now : day.startOn(now.toLocalDate());
        boolean moved = true;
        while (moved && from.isBefore(dayEnd)) {
            moved = false;
            for (TimeOff off : timeOff) {
                if (off.covers(from)) {
                    from = off.endsAt();
                    moved = true;
                }
            }
        }
        return from.isBefore(dayEnd) ? Optional.empty() : Optional.of(Unavailable.NOT_WORKING_TODAY);
    }

    /**
     * Minutes the doctor is scheduled to see patients on a day: the working hours minus the
     * break and any time off. Empty when no schedule is set (nothing to measure against).
     */
    public Optional<Long> workingMinutes(LocalDate date) {
        if (!isConfigured()) return Optional.empty();
        WorkingHours day = week.get(date.getDayOfWeek());
        if (day == null) return Optional.of(0L);
        List<LocalDateTime[]> open = new ArrayList<>();
        if (day.hasBreak()) {
            open.add(new LocalDateTime[]{day.startOn(date), date.atTime(day.breakStart())});
            open.add(new LocalDateTime[]{date.atTime(day.breakEnd()), day.endOn(date)});
        } else {
            open.add(new LocalDateTime[]{day.startOn(date), day.endOn(date)});
        }
        for (TimeOff off : timeOff) {
            List<LocalDateTime[]> remaining = new ArrayList<>();
            for (LocalDateTime[] span : open) {
                if (!off.overlaps(span[0], span[1])) {
                    remaining.add(span);
                    continue;
                }
                if (span[0].isBefore(off.startsAt())) remaining.add(new LocalDateTime[]{span[0], off.startsAt()});
                if (off.endsAt().isBefore(span[1])) remaining.add(new LocalDateTime[]{off.endsAt(), span[1]});
            }
            open = remaining;
        }
        return Optional.of(open.stream().mapToLong(span -> Duration.between(span[0], span[1]).toMinutes()).sum());
    }

    /**
     * How many minutes of pause (before the day starts, the break, time off) lie ahead of a
     * patient who needs {@code serviceMinutes} of the doctor's working time before being
     * called, starting at {@code from}. A wait estimate assumes the doctor works
     * continuously; adding this pushes it past pauses the model knows nothing about.
     * Counts only up to the end of the day's hours.
     */
    public long pauseMinutesAhead(LocalDateTime from, long serviceMinutes) {
        if (!isConfigured() || serviceMinutes <= 0) return 0;
        WorkingHours day = week.get(from.getDayOfWeek());
        if (day == null) return 0;
        LocalDateTime dayStart = day.startOn(from.toLocalDate());
        LocalDateTime dayEnd = day.endOn(from.toLocalDate());
        long paused = 0;
        long served = 0;
        // Minute steps: at most one working day (under 1,440 iterations), and no double
        // counting where the break and time off overlap.
        for (LocalDateTime t = from; served < serviceMinutes && t.isBefore(dayEnd); t = t.plusMinutes(1)) {
            LocalTime clock = t.toLocalTime();
            LocalDateTime minute = t;
            boolean pause = t.isBefore(dayStart)
                    || (day.hasBreak() && !clock.isBefore(day.breakStart()) && clock.isBefore(day.breakEnd()))
                    || timeOff.stream().anyMatch(off -> off.covers(minute));
            if (pause) paused++;
            else served++;
        }
        return paused;
    }

    /** Slot start times on a day: every slot length from the start, skipping the break. */
    public List<LocalDateTime> slotStarts(LocalDate date) {
        WorkingHours day = week.get(date.getDayOfWeek());
        if (day == null) return List.of();
        List<LocalDateTime> starts = new ArrayList<>();
        LocalDateTime cursor = day.startOn(date);
        LocalDateTime dayEnd = day.endOn(date);
        while (!cursor.plus(appointmentLength()).isAfter(dayEnd)) {
            LocalDateTime end = cursor.plus(appointmentLength());
            if (day.overlapsBreak(cursor.toLocalTime(), end.toLocalTime())) {
                // Resume right after the break rather than at the next grid point.
                cursor = date.atTime(day.breakEnd());
                continue;
            }
            starts.add(cursor);
            cursor = end;
        }
        return starts;
    }
}
