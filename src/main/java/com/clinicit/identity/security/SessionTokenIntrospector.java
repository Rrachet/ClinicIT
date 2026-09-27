package com.clinicit.identity.security;

import com.clinicit.identity.application.AuthService;
import org.springframework.security.oauth2.core.OAuth2AuthenticatedPrincipal;
import org.springframework.security.oauth2.server.resource.introspection.BadOpaqueTokenException;
import org.springframework.security.oauth2.server.resource.introspection.OpaqueTokenIntrospector;
import org.springframework.stereotype.Component;

/**
 * Plugs ClinicIT's own session tokens into Spring Security's standard bearer-token
 * filter. Runs on every authenticated request: one indexed lookup of the token hash,
 * which is what lets logout and account disabling take effect immediately.
 */
@Component
public class SessionTokenIntrospector implements OpaqueTokenIntrospector {

    private final AuthService auth;

    public SessionTokenIntrospector(AuthService auth) {
        this.auth = auth;
    }

    @Override
    public OAuth2AuthenticatedPrincipal introspect(String token) {
        return auth.authenticate(token)
                .map(StaffPrincipal::new)
                .orElseThrow(() -> new BadOpaqueTokenException("Invalid, expired or revoked token"));
    }
}
