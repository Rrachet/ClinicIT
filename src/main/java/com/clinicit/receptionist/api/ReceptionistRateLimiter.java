package com.clinicit.receptionist.api;

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
 * Per-client-IP limit on AI receptionist messages: each one is a paid Claude API call.
 * Generous enough for a waiting room sharing one Wi-Fi address; it stops floods and scripts.
 * In memory per instance; addresses are kept only for the current window and never logged.
 */
@Component
@EnableConfigurationProperties(ReceptionistRateLimiter.Properties.class)
public class ReceptionistRateLimiter {

    private static final int MAX_TRACKED = 50_000;

    private final Properties properties;
    private final Clock clock;
    private final Counter rejected;
    private final Map<String, Window> windows = new ConcurrentHashMap<>();

    @ConfigurationProperties("clinicit.receptionist.rate-limit")
    public record Properties(Duration window, Integer maxPerIp) {
        public Properties {
            window = window == null ? Duration.ofMinutes(10) : window;
            maxPerIp = maxPerIp == null ? 80 : maxPerIp;
        }
    }

    private record Window(Instant startedAt, int count) {}

    public ReceptionistRateLimiter(Properties properties, Clock clock, MeterRegistry registry) {
        this.properties = properties;
        this.clock = clock;
        this.rejected = Counter.builder("clinicit.receptionist.rejected")
                .description("Receptionist chat messages refused by the per-IP rate limit")
                .register(registry);
    }

    /** Counts one check-in; returns how long to wait, or {@link Duration#ZERO} to proceed. */
    public Duration tryAcquire(String clientIp) {
        Instant now = clock.instant();
        if (windows.size() > MAX_TRACKED) {
            windows.values().removeIf(w -> expired(w, now));
            if (windows.size() > MAX_TRACKED) windows.clear();
        }
        Window window = windows.compute(clientIp == null ? "unknown" : clientIp, (ip, current) ->
                current == null || expired(current, now) ? new Window(now, 1) : new Window(current.startedAt(), current.count() + 1));
        if (window.count() > properties.maxPerIp()) {
            rejected.increment();
            Duration left = Duration.between(now, window.startedAt().plus(properties.window()));
            return left.isNegative() || left.isZero() ? Duration.ofSeconds(1) : left;
        }
        return Duration.ZERO;
    }

    public void reset() {
        windows.clear();
    }

    private boolean expired(Window window, Instant now) {
        return !now.isBefore(window.startedAt().plus(properties.window()));
    }
}
