package com.clinicit.identity.api;

import com.clinicit.identity.application.UserService;
import com.clinicit.identity.domain.Actor;
import com.clinicit.identity.security.AdminOnly;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/users")
public class UserController {

    private final UserService service;

    public UserController(UserService service) {
        this.service = service;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @AdminOnly
    public UserResponse create(Actor actor, @Valid @RequestBody CreateUserRequest request) {
        return service.create(actor, request);
    }

    @GetMapping
    @AdminOnly
    public List<UserResponse> list(Actor actor) {
        return service.list(actor);
    }

    @PostMapping("/{id}/disable")
    @AdminOnly
    public UserResponse disable(Actor actor, @PathVariable UUID id) {
        return service.disable(actor, id);
    }
}
