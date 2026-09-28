package com.clinicit.queue.api;

import com.clinicit.common.api.ApiError;
import com.clinicit.common.domain.NotFoundException;
import com.clinicit.queue.application.PublicQueueStatusService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Duration;
import java.time.Instant;

/**
 * Public (no login) patient status, for the link or QR code the front desk gives a patient.
 * Deliberately separate from the staff WebSocket topics: patients poll this endpoint.
 * Read-only, and rate limited per client address ({@link PublicStatusRateLimiter}).
 */
@RestController
@RequestMapping("/api/v1/public/queue-status")
public class PublicQueueStatusController {

    private final PublicQueueStatusService service;
    private final PublicStatusRateLimiter limiter;

    public PublicQueueStatusController(PublicQueueStatusService service, PublicStatusRateLimiter limiter) {
        this.service = service;
        this.limiter = limiter;
    }

    @GetMapping("/{code}")
    public ResponseEntity<?> status(@PathVariable String code, HttpServletRequest http) {
        // The real client address: server.forward-headers-strategy resolves X-Forwarded-For
        // from the trusted proxy in production.
        String clientIp = http.getRemoteAddr();
        Duration retryAfter = limiter.tryAcquire(clientIp);
        if (!retryAfter.isZero()) {
            return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS)
                    .header(HttpHeaders.RETRY_AFTER, Long.toString(Math.max(1, retryAfter.toSeconds())))
                    .cacheControl(CacheControl.noStore())
                    .body(new ApiError(Instant.now(), 429, "RATE_LIMITED",
                            "Too many requests, please try again shortly", "/api/v1/public/queue-status"));
        }
        try {
            if (!code.matches("[A-Za-z0-9_-]{16,32}")) {
                throw new NotFoundException("Queue status not found");
            }
            return ResponseEntity.ok()
                    .cacheControl(CacheControl.noStore())
                    .body(service.status(code));
        } catch (NotFoundException e) {
            limiter.recordMiss(clientIp);
            throw e;
        }
    }
}
