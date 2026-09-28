package com.clinicit.schedule.domain;

import com.clinicit.common.domain.InvalidRequestException;
import org.junit.jupiter.api.Test;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class DoctorScheduleTest {

    // Tuesday.
    static final LocalDate DAY = LocalDate.of(2026, 3, 10);
    static final LocalDateTime EARLY = DAY.atTime(7, 0);

    static WorkingHours tuesday() {
        return new WorkingHours(DayOfWeek.TUESDAY, LocalTime.of(9, 0), LocalTime.of(13, 0),
                LocalTime.of(11, 0), LocalTime.of(11, 30));
    }

    static DoctorSchedule schedule(TimeOff... off) {
        return new DoctorSchedule(UUID.randomUUID(), 15, List.of(tuesday()), List.of(off));
    }

    static TimeOff off(LocalDateTime from, LocalDateTime to) {
        return new TimeOff(UUID.randomUUID(), null, from, to, null);
    }

    static Optional<Unavailable> slot(DoctorSchedule schedule, int hour, int minute) {
        return schedule.checkSlot(DAY.atTime(hour, minute), EARLY);
    }

    @Test
    void noWeeklyHoursMeansNothingIsEnforced() {
        DoctorSchedule none = new DoctorSchedule(UUID.randomUUID(), 15, List.of(), List.of());
        assertThat(none.isConfigured()).isFalse();
        assertThat(none.checkSlot(DAY.atTime(3, 0), DAY.atTime(23, 0))).isEmpty();
        assertThat(none.checkWalkIn(DAY.atTime(23, 59))).isEmpty();
    }

    @Test
    void aSlotMustFitInsideTheHoursAndMissTheBreak() {
        DoctorSchedule s = schedule();
        assertThat(slot(s, 9, 0)).isEmpty();
        assertThat(slot(s, 10, 45)).isEmpty();                              // ends exactly at the break
        assertThat(slot(s, 11, 30)).isEmpty();                              // starts exactly after it
        assertThat(slot(s, 12, 45)).isEmpty();                              // ends exactly at closing
        assertThat(slot(s, 8, 59)).contains(Unavailable.OUTSIDE_HOURS);
        assertThat(slot(s, 12, 50)).contains(Unavailable.OUTSIDE_HOURS);    // would run past 13:00
        assertThat(slot(s, 13, 0)).contains(Unavailable.OUTSIDE_HOURS);
        assertThat(slot(s, 10, 50)).contains(Unavailable.ON_BREAK);         // runs into the break
        assertThat(slot(s, 11, 15)).contains(Unavailable.ON_BREAK);
        assertThat(s.checkSlot(DAY.plusDays(1).atTime(10, 0), EARLY)).contains(Unavailable.DAY_OFF);
    }

    @Test
    void timeOffAndThePastAreRefused() {
        DoctorSchedule s = schedule(off(DAY.atTime(12, 0), DAY.atTime(12, 30)));
        assertThat(slot(s, 11, 45)).isEmpty();                              // ends as time off starts
        assertThat(slot(s, 11, 50)).contains(Unavailable.TIME_OFF);
        assertThat(slot(s, 12, 15)).contains(Unavailable.TIME_OFF);
        assertThat(slot(s, 12, 30)).isEmpty();
        assertThat(s.checkSlot(DAY.atTime(9, 0), DAY.atTime(9, 1))).contains(Unavailable.IN_THE_PAST);
    }

    @Test
    void slotsFollowTheGridAndResumeAfterTheBreak() {
        DoctorSchedule s = new DoctorSchedule(UUID.randomUUID(), 20, List.of(tuesday()), List.of());
        assertThat(s.slotStarts(DAY)).extracting(LocalDateTime::toLocalTime).containsExactly(
                LocalTime.of(9, 0), LocalTime.of(9, 20), LocalTime.of(9, 40), LocalTime.of(10, 0),
                LocalTime.of(10, 20), LocalTime.of(10, 40),
                // 11:00-11:30 break; 20-minute slots restart at 11:30
                LocalTime.of(11, 30), LocalTime.of(11, 50), LocalTime.of(12, 10), LocalTime.of(12, 30));
        assertThat(s.slotStarts(DAY.plusDays(1))).isEmpty();
    }

    @Test
    void walkInsWaitThroughBreaksAndShortAbsencesButNotPastTheDay() {
        DoctorSchedule s = schedule(off(DAY.atTime(9, 0), DAY.atTime(10, 0)));
        assertThat(s.checkWalkIn(DAY.atTime(8, 30))).isEmpty();             // early: waits for opening
        assertThat(s.checkWalkIn(DAY.atTime(9, 30))).isEmpty();             // doctor back at 10:00
        assertThat(s.checkWalkIn(DAY.atTime(11, 10))).isEmpty();            // during the break
        assertThat(s.checkWalkIn(DAY.atTime(13, 0))).contains(Unavailable.NOT_WORKING_TODAY);
        assertThat(s.checkWalkIn(DAY.plusDays(1).atTime(10, 0))).contains(Unavailable.DAY_OFF);

        DoctorSchedule leaveRestOfDay = schedule(off(DAY.atTime(10, 0), DAY.atTime(11, 0)),
                off(DAY.atTime(11, 0), DAY.plusDays(2).atStartOfDay()));
        assertThat(leaveRestOfDay.checkWalkIn(DAY.atTime(9, 30))).isEmpty();
        assertThat(leaveRestOfDay.checkWalkIn(DAY.atTime(10, 5))).contains(Unavailable.NOT_WORKING_TODAY);
    }

    @Test
    void invalidHoursAreRejected() {
        assertThatThrownBy(() -> new WorkingHours(DayOfWeek.MONDAY, LocalTime.of(13, 0), LocalTime.of(9, 0), null, null))
                .isInstanceOf(InvalidRequestException.class);
        assertThatThrownBy(() -> new WorkingHours(DayOfWeek.MONDAY, LocalTime.of(9, 0), LocalTime.of(13, 0),
                LocalTime.of(12, 0), null)).isInstanceOf(InvalidRequestException.class);
        assertThatThrownBy(() -> new WorkingHours(DayOfWeek.MONDAY, LocalTime.of(9, 0), LocalTime.of(13, 0),
                LocalTime.of(12, 30), LocalTime.of(13, 30))).isInstanceOf(InvalidRequestException.class);
        assertThatThrownBy(() -> new WorkingHours(DayOfWeek.MONDAY, LocalTime.of(9, 0), LocalTime.of(13, 0),
                LocalTime.of(9, 0), LocalTime.of(9, 30))).isInstanceOf(InvalidRequestException.class);
    }

    @Test
    void workingMinutesAreTheHoursLessTheBreakAndTimeOff() {
        assertThat(schedule().workingMinutes(DAY)).contains(210L);                       // 9-13 less 30 min
        assertThat(schedule().workingMinutes(DAY.plusDays(1))).contains(0L);             // day off
        assertThat(schedule(off(DAY.atTime(12, 0), DAY.atTime(15, 0))).workingMinutes(DAY)).contains(150L);
        // Leave covering the break is not subtracted twice.
        assertThat(schedule(off(DAY.atTime(10, 45), DAY.atTime(11, 45))).workingMinutes(DAY)).contains(180L);
        assertThat(new DoctorSchedule(UUID.randomUUID(), 15, List.of(), List.of()).workingMinutes(DAY)).isEmpty();
    }

    @Test
    void pausesAheadOfAPatientAreCountedOnceAndOnlyWhileTheDayLasts() {
        DoctorSchedule s = schedule();
        assertThat(s.pauseMinutesAhead(DAY.atTime(10, 0), 30)).isZero();                // done before the break
        assertThat(s.pauseMinutesAhead(DAY.atTime(10, 50), 20)).isEqualTo(30);          // 10 min, break, 10 min
        assertThat(s.pauseMinutesAhead(DAY.atTime(8, 30), 10)).isEqualTo(30);           // doctor starts at 9
        assertThat(s.pauseMinutesAhead(DAY.atTime(11, 10), 5)).isEqualTo(20);           // arrived during the break

        DoctorSchedule overlapping = schedule(off(DAY.atTime(10, 45), DAY.atTime(11, 45)));
        assertThat(overlapping.pauseMinutesAhead(DAY.atTime(10, 30), 30)).isEqualTo(60); // one hour away, not 90

        assertThat(s.pauseMinutesAhead(DAY.atTime(12, 50), 60)).isZero();               // the day ends first
        assertThat(s.pauseMinutesAhead(DAY.plusDays(1).atTime(10, 0), 30)).isZero();    // no hours that day
    }
}
