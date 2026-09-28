package com.clinicit.schedule.api;

import com.clinicit.schedule.domain.DoctorSchedule;
import com.clinicit.schedule.domain.TimeOff;
import com.clinicit.schedule.domain.WorkingHours;

import java.time.DayOfWeek;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;

/** A doctor's weekly hours (days without a row are days off) and current and upcoming time off. */
public record ScheduleResponse(UUID doctorId, int appointmentMinutes, List<Day> weeklyHours, List<TimeOffResponse> timeOff) {

    public record Day(DayOfWeek dayOfWeek, LocalTime start, LocalTime end, LocalTime breakStart, LocalTime breakEnd) {
        static Day from(WorkingHours hours) {
            return new Day(hours.dayOfWeek(), hours.start(), hours.end(), hours.breakStart(), hours.breakEnd());
        }

        public WorkingHours toDomain() {
            return new WorkingHours(dayOfWeek, start, end, breakStart, breakEnd);
        }
    }

    public record TimeOffResponse(UUID id, LocalDateTime startsAt, LocalDateTime endsAt, String reason) {
        public static TimeOffResponse from(TimeOff off) {
            return new TimeOffResponse(off.id(), off.startsAt(), off.endsAt(), off.reason());
        }
    }

    public static ScheduleResponse from(DoctorSchedule schedule) {
        return new ScheduleResponse(schedule.doctorId(), schedule.appointmentMinutes(),
                schedule.weeklyHours().stream().sorted(Comparator.comparing(WorkingHours::dayOfWeek)).map(Day::from).toList(),
                schedule.timeOff().stream().map(TimeOffResponse::from).toList());
    }
}
