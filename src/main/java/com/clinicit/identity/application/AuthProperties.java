package com.clinicit.identity.application;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/**
 * @param sessionTtl how long a login token stays valid (absolute, not sliding)
 */
@ConfigurationProperties("clinicit.auth")
public record AuthProperties(Duration sessionTtl) {

    public AuthProperties {
        if (sessionTtl == null) {
            sessionTtl = Duration.ofHours(12);
        }
        if (sessionTtl.isNegative() || sessionTtl.isZero()) {
            throw new IllegalArgumentException("clinicit.auth.session-ttl must be positive");
        }
    }
}
