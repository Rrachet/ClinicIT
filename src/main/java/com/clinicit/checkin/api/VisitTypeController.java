package com.clinicit.checkin.api;

import com.clinicit.checkin.application.VisitTypeService;
import com.clinicit.identity.domain.Actor;
import com.clinicit.identity.security.AdminOnly;
import com.clinicit.identity.security.AnyStaff;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/visit-types")
public class VisitTypeController {

    private final VisitTypeService service;

    public VisitTypeController(VisitTypeService service) {
        this.service = service;
    }

    @GetMapping
    @AnyStaff
    public List<VisitTypeResponse> list(Actor actor) {
        return service.list(actor);
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @AdminOnly
    public VisitTypeResponse create(Actor actor, @Valid @RequestBody VisitTypeRequest request) {
        return service.create(actor, request);
    }

    @PutMapping("/{id}")
    @AdminOnly
    public VisitTypeResponse update(Actor actor, @PathVariable UUID id, @Valid @RequestBody VisitTypeRequest request) {
        return service.update(actor, id, request);
    }
}
