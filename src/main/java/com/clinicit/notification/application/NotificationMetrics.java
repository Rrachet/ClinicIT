package com.clinicit.notification.application;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * Notification delivery metrics. Tags carry only the outcome, the channel and a short reason
 * code, never a recipient, body or identifier.
 *
 * <ul>
 *   <li>{@code clinicit.notifications.deliveries{outcome=sent|retry|failed, channel, reason}}</li>
 *   <li>{@code clinicit.notifications.pending}: messages waiting to be sent or retried (the backlog)</li>
 * </ul>
 */
@Component
public class NotificationMetrics {

    private final MeterRegistry registry;

    public NotificationMetrics(MeterRegistry registry, JdbcTemplate jdbc) {
        this.registry = registry;
        if (jdbc != null) {
            Gauge.builder("clinicit.notifications.pending", jdbc, NotificationMetrics::pending)
                    .description("Notifications waiting to be sent or retried").register(registry);
        }
    }

    void sent(String channel) {
        count("sent", channel, "none");
    }

    void retryScheduled(String channel, String code) {
        count("retry", channel, reason(code));
    }

    void failed(String channel, String code) {
        count("failed", channel, reason(code));
    }

    private void count(String outcome, String channel, String reason) {
        Counter.builder("clinicit.notifications.deliveries")
                .tag("outcome", outcome).tag("channel", channel).tag("reason", reason)
                .description("Notification delivery outcomes").register(registry).increment();
    }

    /** Bounded reason codes: "PROVIDER_ERROR:SocketTimeoutException" → "PROVIDER_ERROR", etc. */
    static String reason(String code) {
        if (code == null) return "unknown";
        String base = code.contains(":") ? code.substring(0, code.indexOf(':')) : code;
        if (base.startsWith("NO_PROVIDER_FOR_")) return "NO_PROVIDER";
        return base.length() > 40 ? base.substring(0, 40) : base;
    }

    private static double pending(JdbcTemplate jdbc) {
        try {
            Long count = jdbc.queryForObject("select count(*) from notifications where status = 'PENDING'", Long.class);
            return count == null ? 0 : count;
        } catch (RuntimeException databaseUnavailable) {
            return Double.NaN; // readiness reports the database; the gauge just has no value
        }
    }
}
