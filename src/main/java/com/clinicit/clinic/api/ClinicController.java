package com.clinicit.clinic.api;

import com.clinicit.clinic.application.ClinicService;
import com.clinicit.identity.domain.Actor;
import com.clinicit.identity.security.AnyStaff;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import com.clinicit.identity.security.AdminOnly;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/clinic")
public class ClinicController {

    private final ClinicService service;

    public ClinicController(ClinicService service) {
        this.service = service;
    }

    /** The caller's own clinic, including its current local date. */
    @GetMapping
    @AnyStaff
    public ClinicResponse current(Actor actor) {
        return service.current(actor);
    }

    @PutMapping
    @AdminOnly
    public ClinicResponse update(Actor actor, @Valid @RequestBody UpdateClinicRequest request) {
        return service.rename(actor, request.name());
    }

    public record UpdateClinicRequest(@NotBlank @Size(max = 150) String name) {}
}
