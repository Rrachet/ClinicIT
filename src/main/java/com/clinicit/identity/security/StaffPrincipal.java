package com.clinicit.identity.security;

import com.clinicit.identity.domain.Actor;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.core.OAuth2AuthenticatedPrincipal;

import java.util.Collection;
import java.util.List;
import java.util.Map;

/** Adapts an {@link Actor} to what Spring Security's bearer-token filter expects. */
public record StaffPrincipal(Actor actor) implements OAuth2AuthenticatedPrincipal {

    @Override
    public Map<String, Object> getAttributes() {
        return Map.of("sub", actor.userId().toString());
    }

    @Override
    public Collection<? extends GrantedAuthority> getAuthorities() {
        return List.of(new SimpleGrantedAuthority(actor.role().authority()));
    }

    @Override
    public String getName() {
        return actor.userId().toString();
    }
}
