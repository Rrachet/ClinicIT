package com.clinicit.patient.api;

import com.clinicit.identity.domain.Actor;
import com.clinicit.identity.security.AnyStaff;
import com.clinicit.identity.security.FrontDesk;
import com.clinicit.patient.application.PatientService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.springframework.http.HttpStatus;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/patients")
@Validated
public class PatientController {

    private final PatientService service;

    public PatientController(PatientService service) {
        this.service = service;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @FrontDesk
    public PatientResponse create(Actor actor, @Valid @RequestBody PatientRequest request) {
        return service.create(actor, request);
    }

    @GetMapping("/{id}")
    @AnyStaff
    public PatientResponse get(Actor actor, @PathVariable UUID id) {
        return service.get(actor, id);
    }

    @GetMapping
    @FrontDesk
    public List<PatientResponse> search(Actor actor, @RequestParam @NotBlank @Size(max = 150) String name) {
        return service.search(actor, name);
    }
}
