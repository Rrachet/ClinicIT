package com.clinicit.clinic.api;

import com.clinicit.clinic.application.ClinicService;
import com.clinicit.identity.domain.Actor;
import com.clinicit.identity.security.AnyStaff;
import org.springframework.web.bind.annotation.GetMapping;
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
}
