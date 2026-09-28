package com.clinicit.schedule.api;

import com.clinicit.identity.domain.Actor;
import com.clinicit.identity.security.AdminOnly;
import com.clinicit.identity.security.AnyStaff;
import com.clinicit.schedule.application.AvailabilityService;
import com.clinicit.schedule.application.DoctorScheduleService;
import jakarta.validation.Valid;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/doctors/{doctorId}")
public class ScheduleController {

    private final DoctorScheduleService schedules;
    private final AvailabilityService availability;

    public ScheduleController(DoctorScheduleService schedules, AvailabilityService availability) {
        this.schedules = schedules;
        this.availability = availability;
    }

    @GetMapping("/schedule")
    @AnyStaff
    public ScheduleResponse schedule(Actor actor, @PathVariable UUID doctorId) {
        return ScheduleResponse.from(schedules.get(actor, doctorId));
    }

    @PutMapping("/schedule")
    @AdminOnly
    public ScheduleResponse replaceSchedule(Actor actor, @PathVariable UUID doctorId,
                                            @Valid @RequestBody UpdateScheduleRequest request) {
        return ScheduleResponse.from(schedules.replaceWeek(actor, doctorId, request.appointmentMinutes(),
                request.weeklyHours().stream().map(ScheduleResponse.Day::toDomain).toList()));
    }

    @PostMapping("/time-off")
    @ResponseStatus(HttpStatus.CREATED)
    @AdminOnly
    public CreatedTimeOffResponse addTimeOff(Actor actor, @PathVariable UUID doctorId,
                                             @Valid @RequestBody CreateTimeOffRequest request) {
        var timeOff = schedules.addTimeOff(actor, doctorId, request.startsAt(), request.endsAt(), request.reason());
        // Existing bookings are not cancelled: reception decides, with each patient, what to do.
        return new CreatedTimeOffResponse(ScheduleResponse.TimeOffResponse.from(timeOff),
                schedules.bookedDuring(actor, doctorId, timeOff.startsAt(), timeOff.endsAt()));
    }

    /** @param bookedAppointments booked or confirmed appointments inside the new time off, to be moved */
    public record CreatedTimeOffResponse(ScheduleResponse.TimeOffResponse timeOff, int bookedAppointments) {}

    @DeleteMapping("/time-off/{timeOffId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @AdminOnly
    public void removeTimeOff(Actor actor, @PathVariable UUID doctorId, @PathVariable UUID timeOffId) {
        schedules.removeTimeOff(actor, doctorId, timeOffId);
    }

    @GetMapping("/availability")
    @AnyStaff
    public AvailabilityResponse availability(Actor actor, @PathVariable UUID doctorId,
                                             @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date) {
        return availability.forDay(actor, doctorId, date);
    }
}
