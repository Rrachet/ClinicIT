package com.clinicit.appointment.api;

import com.clinicit.appointment.application.AppointmentService;
import com.clinicit.identity.domain.Actor;
import com.clinicit.identity.security.AnyStaff;
import com.clinicit.identity.security.FrontDesk;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/appointments")
public class AppointmentController {

    private final AppointmentService service;

    public AppointmentController(AppointmentService service) {
        this.service = service;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @FrontDesk
    public AppointmentResponse create(Actor actor, @Valid @RequestBody CreateAppointmentRequest request) {
        return service.create(actor, request);
    }

    @GetMapping("/{id}")
    @AnyStaff
    public AppointmentResponse get(Actor actor, @PathVariable UUID id) {
        return service.get(actor, id);
    }

    /** Front desk: whole clinic, or one doctor. Doctors: always their own. */
    @GetMapping
    @AnyStaff
    public List<AppointmentResponse> forDate(
            Actor actor,
            @RequestParam LocalDate date,
            @RequestParam(required = false) UUID doctorId
    ) {
        return service.forDate(actor, doctorId, date);
    }

    @PostMapping("/{id}/confirm")
    @FrontDesk
    public AppointmentResponse confirm(Actor actor, @PathVariable UUID id) {
        return service.confirm(actor, id);
    }

    @PostMapping("/{id}/cancel")
    @FrontDesk
    public AppointmentResponse cancel(Actor actor, @PathVariable UUID id) {
        return service.cancel(actor, id);
    }

    @PostMapping("/{id}/arrive")
    @FrontDesk
    public AppointmentResponse arrive(Actor actor, @PathVariable UUID id) {
        return service.arrive(actor, id);
    }

    /** Only before arrival (BOOKED or CONFIRMED), within the doctor's schedule. */
    @PostMapping("/{id}/reschedule")
    @FrontDesk
    public AppointmentResponse reschedule(Actor actor, @PathVariable UUID id,
                                          @Valid @RequestBody RescheduleAppointmentRequest request) {
        return service.reschedule(actor, id, request.scheduledAt());
    }

    @PostMapping("/{id}/no-show")
    @FrontDesk
    public AppointmentResponse noShow(Actor actor, @PathVariable UUID id) {
        return service.markNoShow(actor, id);
    }
}
