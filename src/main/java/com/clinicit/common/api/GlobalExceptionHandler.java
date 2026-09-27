package com.clinicit.common.api;

import com.clinicit.common.domain.AuthenticationFailedException;
import com.clinicit.common.domain.BusinessRuleException;
import com.clinicit.common.domain.ForbiddenException;
import com.clinicit.common.domain.InvalidRequestException;
import com.clinicit.common.domain.NotFoundException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.ConstraintViolationException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.ConcurrencyFailureException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.ServletWebRequest;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;

import java.time.Instant;

/**
 * Every error response uses the {@link ApiError} shape. Standard Spring MVC errors
 * (malformed JSON, bad path variables, unknown routes, wrong method) are handled by the
 * base class and re-shaped in {@link #handleExceptionInternal}.
 */
@RestControllerAdvice
public class GlobalExceptionHandler extends ResponseEntityExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    @Override
    protected ResponseEntity<Object> handleMethodArgumentNotValid(
            MethodArgumentNotValidException ex, HttpHeaders headers, HttpStatusCode status, WebRequest request
    ) {
        String message = ex.getBindingResult().getFieldErrors().stream()
                .findFirst()
                .map(error -> error.getField() + ": " + error.getDefaultMessage())
                .orElse("Validation failed");

        return body(HttpStatus.BAD_REQUEST, "VALIDATION_ERROR", message, path(request));
    }

    @Override
    protected ResponseEntity<Object> handleExceptionInternal(
            Exception ex, Object body, HttpHeaders headers, HttpStatusCode status, WebRequest request
    ) {
        String message = body instanceof ProblemDetail problem && problem.getDetail() != null
                ? problem.getDetail()
                : HttpStatus.valueOf(status.value()).getReasonPhrase();

        return ResponseEntity.status(status).headers(headers)
                .body(apiError(status.value(), codeFor(status), message, path(request)));
    }

    @ExceptionHandler(ConstraintViolationException.class)
    ResponseEntity<Object> handleConstraintViolation(ConstraintViolationException ex, HttpServletRequest request) {
        return body(HttpStatus.BAD_REQUEST, "VALIDATION_ERROR", ex.getMessage(), request.getRequestURI());
    }

    @ExceptionHandler(InvalidRequestException.class)
    ResponseEntity<Object> handleInvalidRequest(InvalidRequestException ex, HttpServletRequest request) {
        return body(HttpStatus.BAD_REQUEST, "VALIDATION_ERROR", ex.getMessage(), request.getRequestURI());
    }

    @ExceptionHandler(AuthenticationFailedException.class)
    ResponseEntity<Object> handleLoginFailed(AuthenticationFailedException ex, HttpServletRequest request) {
        return body(HttpStatus.UNAUTHORIZED, "INVALID_CREDENTIALS", ex.getMessage(), request.getRequestURI());
    }

    // Security exceptions thrown inside controllers (e.g. by @PreAuthorize) reach this advice
    // before Spring Security's filters, so they are mapped here; otherwise the catch-all
    // below would turn them into 500s.
    @ExceptionHandler(AuthenticationException.class)
    ResponseEntity<Object> handleUnauthenticated(AuthenticationException ex, HttpServletRequest request) {
        return body(HttpStatus.UNAUTHORIZED, "UNAUTHORIZED", "Authentication required", request.getRequestURI());
    }

    @ExceptionHandler(AccessDeniedException.class)
    ResponseEntity<Object> handleAccessDenied(AccessDeniedException ex, HttpServletRequest request) {
        return body(HttpStatus.FORBIDDEN, "FORBIDDEN", "Access denied", request.getRequestURI());
    }

    @ExceptionHandler(ForbiddenException.class)
    ResponseEntity<Object> handleForbidden(ForbiddenException ex, HttpServletRequest request) {
        return body(HttpStatus.FORBIDDEN, "FORBIDDEN", ex.getMessage(), request.getRequestURI());
    }

    @ExceptionHandler(NotFoundException.class)
    ResponseEntity<Object> handleNotFound(NotFoundException ex, HttpServletRequest request) {
        return body(HttpStatus.NOT_FOUND, "NOT_FOUND", ex.getMessage(), request.getRequestURI());
    }

    @ExceptionHandler(BusinessRuleException.class)
    ResponseEntity<Object> handleBusinessRule(BusinessRuleException ex, HttpServletRequest request) {
        return body(HttpStatus.CONFLICT, ex.getCode(), ex.getMessage(), request.getRequestURI());
    }

    // Lock/version conflicts that survived the service-level checks. Safe for the client to retry.
    @ExceptionHandler(ConcurrencyFailureException.class)
    ResponseEntity<Object> handleConcurrency(ConcurrencyFailureException ex, HttpServletRequest request) {
        return body(HttpStatus.CONFLICT, "CONCURRENT_MODIFICATION",
                "The resource was modified concurrently, please retry", request.getRequestURI());
    }

    // A database constraint caught what the service did not. Do not echo SQL details to the client.
    @ExceptionHandler(DataIntegrityViolationException.class)
    ResponseEntity<Object> handleDataIntegrity(DataIntegrityViolationException ex, HttpServletRequest request) {
        return body(HttpStatus.CONFLICT, "CONSTRAINT_VIOLATION",
                "The request conflicts with the current state of the data", request.getRequestURI());
    }

    // Anything else is a bug: log it, but never leak internals to the client.
    @ExceptionHandler(Exception.class)
    ResponseEntity<Object> handleUnexpected(Exception ex, HttpServletRequest request) {
        log.error("Unhandled error on {} {}", request.getMethod(), request.getRequestURI(), ex);
        return body(HttpStatus.INTERNAL_SERVER_ERROR, "INTERNAL_ERROR", "Unexpected error", request.getRequestURI());
    }

    private static String codeFor(HttpStatusCode status) {
        return switch (status.value()) {
            case 400 -> "BAD_REQUEST";
            case 404 -> "NOT_FOUND";
            case 405 -> "METHOD_NOT_ALLOWED";
            case 406 -> "NOT_ACCEPTABLE";
            case 415 -> "UNSUPPORTED_MEDIA_TYPE";
            default -> status.is5xxServerError() ? "INTERNAL_ERROR" : "HTTP_" + status.value();
        };
    }

    private static String path(WebRequest request) {
        return request instanceof ServletWebRequest servlet ? servlet.getRequest().getRequestURI() : null;
    }

    private static ResponseEntity<Object> body(HttpStatus status, String code, String message, String path) {
        return ResponseEntity.status(status).body(apiError(status.value(), code, message, path));
    }

    private static ApiError apiError(int status, String code, String message, String path) {
        return new ApiError(Instant.now(), status, code, message, path);
    }
}
