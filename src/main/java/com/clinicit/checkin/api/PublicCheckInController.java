package com.clinicit.checkin.api;

import com.clinicit.checkin.application.SelfCheckInService;
import com.clinicit.common.api.ApiError;
import com.clinicit.queue.api.PublicStatusRateLimiter;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.Duration;
import java.time.Instant;

/**
 * Public (no login) self check-in, opened from the QR code in the clinic
 * (docs/SELF_CHECK_IN.md). Rate limited per client address.
 */
@RestController
@RequestMapping("/api/v1/public/check-in")
public class PublicCheckInController {

    private static final String PATH = "/api/v1/public/check-in";

    private final SelfCheckInService service;
    private final CheckInRateLimiter checkIns;
    private final PublicStatusRateLimiter reads;

    public PublicCheckInController(SelfCheckInService service, CheckInRateLimiter checkIns, PublicStatusRateLimiter reads) {
        this.service = service;
        this.checkIns = checkIns;
        this.reads = reads;
    }

    @GetMapping("/{code}")
    public ResponseEntity<?> page(@PathVariable String code, HttpServletRequest http) {
        Duration wait = reads.tryAcquire(http.getRemoteAddr());
        if (!wait.isZero()) return tooMany(wait);
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(service.page(code));
    }

    @PostMapping("/{code}")
    public ResponseEntity<?> checkIn(@PathVariable String code, @Valid @RequestBody SelfCheckInRequest request,
                                     HttpServletRequest http) {
        Duration wait = checkIns.tryAcquire(http.getRemoteAddr());
        if (!wait.isZero()) return tooMany(wait);
        SelfCheckInResponse result = service.checkIn(code, request);
        return ResponseEntity.status(result.alreadyCheckedIn() ? HttpStatus.OK : HttpStatus.CREATED)
                .cacheControl(CacheControl.noStore())
                .body(result);
    }

    private static ResponseEntity<ApiError> tooMany(Duration wait) {
        return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS)
                .header(HttpHeaders.RETRY_AFTER, Long.toString(Math.max(1, wait.toSeconds())))
                .cacheControl(CacheControl.noStore())
                .body(new ApiError(Instant.now(), 429, "RATE_LIMITED", "Too many requests, please try again shortly", PATH));
    }
}
