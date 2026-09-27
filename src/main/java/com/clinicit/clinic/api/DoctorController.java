package com.clinicit.clinic.api;

import com.clinicit.clinic.application.DoctorService;
import com.clinicit.identity.domain.Actor;
import com.clinicit.identity.security.AdminOnly;
import com.clinicit.identity.security.AnyStaff;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/v1/doctors")
public class DoctorController {

    private final DoctorService service;

    public DoctorController(DoctorService service) {
        this.service = service;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @AdminOnly
    public DoctorResponse create(Actor actor, @Valid @RequestBody CreateDoctorRequest request) {
        return service.create(actor, request);
    }

    @GetMapping
    @AnyStaff
    public List<DoctorResponse> list(Actor actor) {
        return service.list(actor);
    }
}
