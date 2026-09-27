package com.clinicit.identity.domain;

import java.util.UUID;

/**
 * Raised (inside the revoking transaction) when login sessions stop being valid, so
 * long-lived connections such as WebSockets can be closed once it commits.
 *
 * @param tokenHash the one revoked session, or null for all of the user's sessions
 */
public record SessionsRevoked(UUID userId, String tokenHash) {

    public static SessionsRevoked allOf(UUID userId) {
        return new SessionsRevoked(userId, null);
    }

    public boolean covers(UUID sessionUserId, String sessionTokenHash) {
        return userId.equals(sessionUserId) && (tokenHash == null || tokenHash.equals(sessionTokenHash));
    }
}
