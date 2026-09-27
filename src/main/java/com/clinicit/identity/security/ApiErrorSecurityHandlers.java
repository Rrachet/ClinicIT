package com.clinicit.identity.security;

import com.clinicit.common.api.ApiError;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.oauth2.server.resource.web.BearerTokenAuthenticationEntryPoint;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.security.web.access.AccessDeniedHandler;

import java.io.IOException;
import java.time.Instant;

/**
 * 401/403 raised by the security filters (before any controller runs) use the same
 * {@link ApiError} body as everything else. The 401 also carries the standard
 * {@code WWW-Authenticate: Bearer ...} header.
 */
public class ApiErrorSecurityHandlers implements AuthenticationEntryPoint, AccessDeniedHandler {

    private final ObjectMapper json;
    private final BearerTokenAuthenticationEntryPoint bearerHeaders = new BearerTokenAuthenticationEntryPoint();

    public ApiErrorSecurityHandlers(ObjectMapper json) {
        this.json = json;
    }

    @Override
    public void commence(HttpServletRequest request, HttpServletResponse response, AuthenticationException ex)
            throws IOException {
        bearerHeaders.commence(request, response, ex); // sets 401 + WWW-Authenticate
        write(response, HttpStatus.UNAUTHORIZED, "UNAUTHORIZED", "Authentication required", request);
    }

    @Override
    public void handle(HttpServletRequest request, HttpServletResponse response, AccessDeniedException ex)
            throws IOException {
        write(response, HttpStatus.FORBIDDEN, "FORBIDDEN", "Access denied", request);
    }

    private void write(HttpServletResponse response, HttpStatus status, String code, String message,
                       HttpServletRequest request) throws IOException {
        response.setStatus(status.value());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        json.writeValue(response.getOutputStream(),
                new ApiError(Instant.now(), status.value(), code, message, request.getRequestURI()));
    }
}
