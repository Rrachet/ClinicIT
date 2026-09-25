package com.clinicit.appointment.api;

import com.clinicit.appointment.application.AppointmentService;
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
    public AppointmentResponse create(@Valid @RequestBody CreateAppointmentRequest request) {
        return service.create(request);
    }

    @GetMapping("/{id}")
    public AppointmentResponse get(@PathVariable UUID id) {
        return service.get(id);
    }

    @GetMapping
    public List<AppointmentResponse> forDate(
            @RequestParam UUID clinicId,
            @RequestParam LocalDate date,
            @RequestParam(required = false) UUID doctorId
    ) {
        return service.forDate(clinicId, doctorId, date);
    }

    @PostMapping("/{id}/confirm")
    public AppointmentResponse confirm(@PathVariable UUID id) {
        return service.confirm(id);
    }

    @PostMapping("/{id}/cancel")
    public AppointmentResponse cancel(@PathVariable UUID id) {
        return service.cancel(id);
    }

    @PostMapping("/{id}/arrive")
    public AppointmentResponse arrive(@PathVariable UUID id) {
        return service.arrive(id);
    }
}
