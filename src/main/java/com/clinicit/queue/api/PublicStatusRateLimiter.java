package com.clinicit.queue.api;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Per-client-IP limits for the anonymous patient status page.
 *
 * <p>Two budgets per fixed window:
 * <ul>
 *   <li><b>requests</b>: generous, because one waiting-room Wi-Fi can put many patients behind
 *       one address, each page refreshing every few seconds. It only stops floods.</li>
 *   <li><b>misses</b> (unknown codes): small. Real patients open their own valid link; a
 *       stream of wrong codes is someone guessing. Codes carry 128 random bits, so guessing is
 *       hopeless anyway; this keeps it from costing database queries.</li>
 * </ul>
 * Once either budget is spent, every status request from that address gets 429 until the
 * window ends.
 *
 * <p>In memory, per instance, like the rest of the real-time path (ClinicIT runs as one
 * instance, docs/OPERATIONS.md). Nothing is written to the database for an anonymous GET.
 * Addresses are held only for the current window and are never logged.
 */
@Component
@EnableConfigurationProperties(PublicStatusRateLimiter.Properties.class)
public class PublicStatusRateLimiter {

    private static final int MAX_TRACKED = 50_000;

    private final Properties properties;
    private final Clock clock;
    private final Counter rejected;
    private final Map<String, Window> windows = new ConcurrentHashMap<>();

    @ConfigurationProperties("clinicit.public-status")
    public record Properties(Duration window, Integer maxRequestsPerIp, Integer maxMissesPerIp) {
        public Properties {
            window = window == null ? Duration.ofMinutes(1) : window;
            maxRequestsPerIp = maxRequestsPerIp == null ? 300 : maxRequestsPerIp;
            maxMissesPerIp = maxMissesPerIp == null ? 20 : maxMissesPerIp;
        }
    }

    private static final class Window {
        final Instant startedAt;
        int requests;
        int misses;

        Window(Instant startedAt) {
            this.startedAt = startedAt;
        }
    }

    public PublicStatusRateLimiter(Properties properties, Clock clock, MeterRegistry registry) {
        this.properties = properties;
        this.clock = clock;
        this.rejected = Counter.builder("clinicit.public_status.rejected")
                .description("Public status requests refused by the per-IP rate limit")
                .register(registry);
    }

    /**
     * Counts one request; returns how long the client must wait, or {@link Duration#ZERO}
     * when the request may proceed.
     */
    public Duration tryAcquire(String clientIp) {
        Instant now = clock.instant();
        if (windows.size() > MAX_TRACKED) evictExpired(now);
        Window window = windows.compute(key(clientIp), (ip, current) ->
                current == null || expired(current, now) ? new Window(now) : current);
        synchronized (window) {
            window.requests++;
            if (window.requests > properties.maxRequestsPerIp() || window.misses >= properties.maxMissesPerIp()) {
                rejected.increment();
                Duration left = Duration.between(now, window.startedAt.plus(properties.window()));
                return left.isNegative() || left.isZero() ? Duration.ofSeconds(1) : left;
            }
            return Duration.ZERO;
        }
    }

    /** Records that this client asked for a code that does not exist. */
    public void recordMiss(String clientIp) {
        Window window = windows.get(key(clientIp));
        if (window == null) return;
        synchronized (window) {
            window.misses++;
        }
    }

    /** Forgets every client (tests). */
    public void reset() {
        windows.clear();
    }

    private boolean expired(Window window, Instant now) {
        return !now.isBefore(window.startedAt.plus(properties.window()));
    }

    private void evictExpired(Instant now) {
        windows.values().removeIf(window -> expired(window, now));
        // Still full (a flood of distinct addresses): start over rather than grow without bound.
        if (windows.size() > MAX_TRACKED) windows.clear();
    }

    private static String key(String clientIp) {
        return clientIp == null ? "unknown" : clientIp;
    }
}
