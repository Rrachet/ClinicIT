package com.clinicit.preview;

import com.clinicit.common.api.ApiError;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.time.Instant;
import java.util.Set;

/**
 * Keeps a public preview read-only. Every API request that would change data is refused with
 * 403 {@code PREVIEW_READ_ONLY}; signing in and out still work, and reads are untouched.
 *
 * <p>It runs after Spring Security, so an unauthenticated write still gets 401, and only
 * signed-in users see this message. Active only when {@code clinicit.preview.enabled=true}
 * and {@code clinicit.preview.allow-writes} is not set.
 */
@Component
@Order(Ordered.LOWEST_PRECEDENCE)
@ConditionalOnExpression("${clinicit.preview.enabled:false} and !${clinicit.preview.allow-writes:false}")
public class PreviewReadOnlyFilter extends OncePerRequestFilter {

    static final String MESSAGE = "This is a read-only preview with fictional data. "
            + "Run the local Docker Compose stack to try changes (see the README).";

    private static final Set<String> READ_METHODS = Set.of("GET", "HEAD", "OPTIONS", "TRACE");
    private static final Set<String> ALLOWED_WRITES = Set.of("/api/v1/auth/login", "/api/v1/auth/logout");

    private final ObjectMapper json;

    public PreviewReadOnlyFilter(ObjectMapper json) {
        this.json = json;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String path = request.getRequestURI();
        return READ_METHODS.contains(request.getMethod())
                || !path.startsWith("/api/")
                || ALLOWED_WRITES.contains(path);
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        response.setStatus(HttpStatus.FORBIDDEN.value());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        json.writeValue(response.getOutputStream(), new ApiError(Instant.now(), HttpStatus.FORBIDDEN.value(),
                "PREVIEW_READ_ONLY", MESSAGE, request.getRequestURI()));
    }
}
