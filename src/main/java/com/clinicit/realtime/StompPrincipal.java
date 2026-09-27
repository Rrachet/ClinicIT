package com.clinicit.realtime;

import com.clinicit.identity.domain.Actor;

import java.security.Principal;

/**
 * Who a WebSocket connection belongs to, fixed at STOMP CONNECT. Keeps only the hash of
 * the token so the connection can be re-validated (and closed) when the token is revoked
 * or expires.
 */
public record StompPrincipal(Actor actor, String tokenHash) implements Principal {

    @Override
    public String getName() {
        return actor.userId().toString();
    }

    @Override
    public String toString() {
        return "StompPrincipal[user=" + actor.userId() + ", role=" + actor.role() + "]";
    }
}
