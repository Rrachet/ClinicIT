package com.clinicit.common.api;

import com.clinicit.common.domain.BusinessRuleException;
import com.clinicit.common.domain.NotFoundException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.ConstraintViolationException;
import org.springframework.dao.ConcurrencyFailureException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.time.Instant;

@RestControllerAdvice
public class GlobalExceptionHandler {

    @ExceptionHandler(MethodArgumentNotValidException.class)
    ResponseEntity<ApiError> handleValidation(MethodArgumentNotValidException ex, HttpServletRequest request) {
        String message = ex.getBindingResult().getFieldErrors().stream()
                .findFirst()
                .map(error -> error.getField() + ": " + error.getDefaultMessage())
                .orElse("Validation failed");

        return error(HttpStatus.BAD_REQUEST, "VALIDATION_ERROR", message, request);
    }

    @ExceptionHandler(ConstraintViolationException.class)
    ResponseEntity<ApiError> handleConstraintViolation(ConstraintViolationException ex, HttpServletRequest request) {
        return error(HttpStatus.BAD_REQUEST, "VALIDATION_ERROR", ex.getMessage(), request);
    }

    @ExceptionHandler(NotFoundException.class)
    ResponseEntity<ApiError> handleNotFound(NotFoundException ex, HttpServletRequest request) {
        return error(HttpStatus.NOT_FOUND, "NOT_FOUND", ex.getMessage(), request);
    }

    @ExceptionHandler(BusinessRuleException.class)
    ResponseEntity<ApiError> handleBusinessRule(BusinessRuleException ex, HttpServletRequest request) {
        return error(HttpStatus.CONFLICT, ex.getCode(), ex.getMessage(), request);
    }

    // Lock/version conflicts that survived the service-level checks. Safe for the client to retry.
    @ExceptionHandler(ConcurrencyFailureException.class)
    ResponseEntity<ApiError> handleConcurrency(ConcurrencyFailureException ex, HttpServletRequest request) {
        return error(HttpStatus.CONFLICT, "CONCURRENT_MODIFICATION",
                "The resource was modified concurrently, please retry", request);
    }

    // A database constraint caught what the service did not. Do not echo SQL details to the client.
    @ExceptionHandler(DataIntegrityViolationException.class)
    ResponseEntity<ApiError> handleDataIntegrity(DataIntegrityViolationException ex, HttpServletRequest request) {
        return error(HttpStatus.CONFLICT, "CONSTRAINT_VIOLATION",
                "The request conflicts with the current state of the data", request);
    }

    @ExceptionHandler(IllegalStateException.class)
    ResponseEntity<ApiError> handleIllegalState(IllegalStateException ex, HttpServletRequest request) {
        return error(HttpStatus.CONFLICT, "INVALID_STATE", ex.getMessage(), request);
    }

    private ResponseEntity<ApiError> error(
            HttpStatus status,
            String code,
            String message,
            HttpServletRequest request
    ) {
        return ResponseEntity.status(status).body(
                new ApiError(Instant.now(), status.value(), code, message, request.getRequestURI())
        );
    }
}
