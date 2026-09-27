package com.clinicit.identity.application;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/**
 * @param sessionTtl             how long a login token stays valid (absolute, not sliding)
 * @param loginWindow            length of the brute-force counting window
 * @param maxAttemptsPerAccount  login attempts per email per window (successful login resets it)
 * @param maxFailuresPerIp       failed logins per client IP per window, across all emails
 */
@ConfigurationProperties("clinicit.auth")
public record AuthProperties(
        Duration sessionTtl,
        Duration loginWindow,
        Integer maxAttemptsPerAccount,
        Integer maxFailuresPerIp
) {

    public AuthProperties {
        sessionTtl = positive(sessionTtl, Duration.ofHours(12), "session-ttl");
        loginWindow = positive(loginWindow, Duration.ofMinutes(15), "login-window");
        maxAttemptsPerAccount = maxAttemptsPerAccount == null ? 5 : maxAttemptsPerAccount;
        maxFailuresPerIp = maxFailuresPerIp == null ? 30 : maxFailuresPerIp;
        if (maxAttemptsPerAccount < 1 || maxFailuresPerIp < 1) {
            throw new IllegalArgumentException("clinicit.auth login limits must be positive");
        }
    }

    private static Duration positive(Duration value, Duration fallback, String name) {
        Duration result = value == null ? fallback : value;
        if (result.isNegative() || result.isZero()) {
            throw new IllegalArgumentException("clinicit.auth." + name + " must be positive");
        }
        return result;
    }
}
