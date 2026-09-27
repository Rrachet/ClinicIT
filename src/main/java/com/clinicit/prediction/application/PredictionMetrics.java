package com.clinicit.prediction.application;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.Set;

/**
 * Wait-estimate and ML-service metrics:
 * <ul>
 *   <li>{@code clinicit.ml.requests{outcome}}: latency of calls to the ML service; outcome is
 *       success, timeout, unreachable, http_error, bad_response or invalid_prediction</li>
 *   <li>{@code clinicit.ml.backoff.skips}: calls not made because the service recently failed</li>
 *   <li>{@code clinicit.wait_estimates{source=MODEL|BASELINE, reason}}: estimates produced; the
 *       BASELINE share is the fallback rate</li>
 *   <li>{@code clinicit.wait_estimates.cache{result=hit|miss}}</li>
 * </ul>
 */
@Component
public class PredictionMetrics {

    /** Reasons ClinicIT knows; anything else the ML service sends is reported as OTHER. */
    static final Set<String> KNOWN_REASONS = Set.of("ML_DISABLED", "ML_UNAVAILABLE", "INVALID_PREDICTION",
            "INSUFFICIENT_HISTORY", "OUT_OF_TRAINING_RANGE", "MODEL_NOT_LOADED");

    private final MeterRegistry registry;
    private final Counter backoffSkips;
    private final Counter cacheHits;
    private final Counter cacheMisses;

    public PredictionMetrics(MeterRegistry registry) {
        this.registry = registry;
        this.backoffSkips = Counter.builder("clinicit.ml.backoff.skips")
                .description("ML calls skipped during the failure back-off").register(registry);
        this.cacheHits = Counter.builder("clinicit.wait_estimates.cache").tag("result", "hit").register(registry);
        this.cacheMisses = Counter.builder("clinicit.wait_estimates.cache").tag("result", "miss").register(registry);
    }

    static String knownReason(String reason) {
        return reason == null ? null : KNOWN_REASONS.contains(reason) ? reason : "OTHER";
    }

    void mlRequest(String outcome, Duration latency) {
        Timer.builder("clinicit.ml.requests").tag("outcome", outcome)
                .description("Calls to the wait-time ML service").register(registry).record(latency);
    }

    void backoffSkip() {
        backoffSkips.increment();
    }

    void cache(int hits, int misses) {
        cacheHits.increment(hits);
        cacheMisses.increment(misses);
    }

    void estimate(String source, String reason) {
        Counter.builder("clinicit.wait_estimates").tag("source", source).tag("reason", reason == null ? "none" : reason)
                .description("Wait estimates produced").register(registry).increment();
    }
}
