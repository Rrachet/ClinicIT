package com.clinicit.patient.api;

import com.clinicit.patient.application.PatientService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/patients")
public class PatientController {

    private final PatientService service;

    public PatientController(PatientService service) {
        this.service = service;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public PatientResponse create(@Valid @RequestBody PatientRequest request) {
        return service.create(request);
    }

    @GetMapping("/{id}")
    public PatientResponse get(@PathVariable UUID id) {
        return service.get(id);
    }

    @GetMapping
    public List<PatientResponse> search(
            @RequestParam UUID clinicId,
            @RequestParam String name
    ) {
        return service.search(clinicId, name);
    }
}
