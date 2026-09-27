package com.clinicit.identity.api;

import com.clinicit.identity.application.AuthService;
import com.clinicit.identity.application.UserService;
import com.clinicit.identity.domain.Actor;
import com.clinicit.identity.security.AnyStaff;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.security.oauth2.server.resource.authentication.BearerTokenAuthentication;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/auth")
public class AuthController {

    private final AuthService auth;
    private final UserService users;

    public AuthController(AuthService auth, UserService users) {
        this.auth = auth;
        this.users = users;
    }

    /** Public. Returns an opaque bearer token for the Authorization header. */
    @PostMapping("/login")
    public LoginResponse login(@Valid @RequestBody LoginRequest request) {
        return auth.login(request.email(), request.password());
    }

    /** Revokes the token used for this request. */
    @PostMapping("/logout")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @AnyStaff
    public void logout(BearerTokenAuthentication authentication) {
        auth.logout(authentication.getToken().getTokenValue());
    }

    @GetMapping("/me")
    @AnyStaff
    public UserResponse me(Actor actor) {
        return users.me(actor);
    }
}
