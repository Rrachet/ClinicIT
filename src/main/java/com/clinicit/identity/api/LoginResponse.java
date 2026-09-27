package com.clinicit.identity.api;

import java.time.Instant;

public record LoginResponse(String accessToken, String tokenType, Instant expiresAt, UserResponse user) {

    @Override
    public String toString() {
        return "LoginResponse[tokenType=" + tokenType + ", expiresAt=" + expiresAt + ", user=" + user + "]";
    }
}
