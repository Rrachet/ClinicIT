package com.clinicit.identity.security;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.net.URI;
import java.util.List;

/**
 * Browser origins (e.g. the Next.js frontend) allowed to call the API and open the
 * WebSocket. Exact origins only: wildcards are rejected at startup. Empty means no
 * cross-origin access at all, which is the safe default.
 */
@ConfigurationProperties("clinicit.cors")
public record CorsProperties(List<String> allowedOrigins) {

    public CorsProperties {
        allowedOrigins = allowedOrigins == null ? List.of() : allowedOrigins.stream()
                .map(String::trim)
                .filter(origin -> !origin.isEmpty())
                .map(CorsProperties::validated)
                .toList();
    }

    private static String validated(String origin) {
        if (origin.contains("*")) {
            throw new IllegalArgumentException("clinicit.cors.allowed-origins must list exact origins, not " + origin);
        }
        URI uri = URI.create(origin);
        boolean exactOrigin = ("https".equals(uri.getScheme()) || "http".equals(uri.getScheme()))
                && uri.getHost() != null
                && (uri.getPath() == null || uri.getPath().isEmpty())
                && uri.getQuery() == null;
        if (!exactOrigin) {
            throw new IllegalArgumentException("Not an origin (scheme://host[:port]): " + origin);
        }
        return origin;
    }
}
