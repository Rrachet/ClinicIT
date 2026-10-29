package com.clinicit.receptionist.api;

import com.clinicit.common.api.ApiError;
import com.clinicit.queue.api.PublicStatusRateLimiter;
import com.clinicit.receptionist.application.ReceptionistService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

/**
 * Public (no login) AI receptionist chat, opened from the clinic's QR code
 * (docs/AI_RECEPTIONIST.md). Rate limited per client address: every message costs a Claude call.
 */
@RestController
@RequestMapping("/api/v1/public/receptionist")
public class PublicReceptionistController {

    private static final String PATH = "/api/v1/public/receptionist";

    private final ReceptionistService service;
    private final ReceptionistRateLimiter messages;
    private final PublicStatusRateLimiter reads;

    public PublicReceptionistController(ReceptionistService service, ReceptionistRateLimiter messages,
                                        PublicStatusRateLimiter reads) {
        this.service = service;
        this.messages = messages;
        this.reads = reads;
    }

    public record MessageRequest(UUID conversationId, @NotBlank @Size(max = 2000) String message) {}

    @GetMapping("/{code}")
    public ResponseEntity<?> info(@PathVariable String code, HttpServletRequest http) {
        Duration wait = reads.tryAcquire(http.getRemoteAddr());
        if (!wait.isZero()) return tooMany(wait);
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(service.info(code));
    }

    @PostMapping("/{code}/messages")
    public ResponseEntity<?> message(@PathVariable String code, @Valid @RequestBody MessageRequest request,
                                     HttpServletRequest http) {
        Duration wait = messages.tryAcquire(http.getRemoteAddr());
        if (!wait.isZero()) return tooMany(wait);
        try {
            return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                    .body(service.chat(code, request.conversationId(), request.message()));
        } catch (ReceptionistService.UnavailableException e) {
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).cacheControl(CacheControl.noStore())
                    .body(new ApiError(Instant.now(), 503, "RECEPTIONIST_UNAVAILABLE", e.getMessage(), PATH));
        }
    }

    private static ResponseEntity<ApiError> tooMany(Duration wait) {
        return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS)
                .header(HttpHeaders.RETRY_AFTER, Long.toString(Math.max(1, wait.toSeconds())))
                .cacheControl(CacheControl.noStore())
                .body(new ApiError(Instant.now(), 429, "RATE_LIMITED", "Too many messages, please wait a moment", PATH));
    }
}
