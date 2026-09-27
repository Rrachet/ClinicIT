package com.clinicit.notification.application;

import com.clinicit.notification.domain.NotificationChannel;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Delivers PENDING notifications through the configured {@link NotificationProvider}.
 *
 * <ul>
 *   <li><b>Fast path</b>: right after the causing transaction commits, on a background
 *       executor, so a slow vendor never slows a queue operation down.</li>
 *   <li><b>Retries</b>: a poller picks up due PENDING rows (after failures, restarts, or
 *       a crash before the fast path ran). Delay doubles per attempt, capped.</li>
 * </ul>
 * Every attempt first <i>claims</i> the row with one atomic UPDATE (pushing its
 * next_attempt_at forward as a lease), so two senders never deliver the same row at once.
 * A message past its expiry is marked FAILED (EXPIRED) and never sent.
 */
@Component
public class NotificationDispatcher {

    private static final Logger log = LoggerFactory.getLogger(NotificationDispatcher.class);
    private static final Duration LEASE = Duration.ofMinutes(2);
    private static final int BATCH = 50;

    private static final String CLAIM_ONE = """
            update notifications set next_attempt_at = ?, updated_at = ?
            where id = ? and status = 'PENDING' and next_attempt_at <= ?
            returning id, channel, recipient, body, attempts, max_attempts, expires_at
            """;

    private static final String CLAIM_DUE = """
            update notifications set next_attempt_at = ?, updated_at = ?
            where id in (select id from notifications
                         where status = 'PENDING' and next_attempt_at <= ?
                         order by next_attempt_at
                         limit %d
                         for update skip locked)
            returning id, channel, recipient, body, attempts, max_attempts, expires_at
            """.formatted(BATCH);

    private final JdbcTemplate jdbc;
    private final NotificationProviders providers;
    private final NotificationProperties properties;
    private final NotificationExecutor executor;
    private final Clock clock;

    public NotificationDispatcher(
            JdbcTemplate jdbc,
            NotificationProviders providers,
            NotificationProperties properties,
            NotificationExecutor executor,
            Clock clock
    ) {
        this.jdbc = jdbc;
        this.providers = providers;
        this.properties = properties;
        this.executor = executor;
        this.clock = clock;
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void afterCommit(NotificationService.Queued queued) {
        executor.submit(() -> queued.notificationIds().forEach(this::sendIfDue));
    }

    @Scheduled(
            initialDelayString = "${clinicit.notifications.poll-interval:PT10S}",
            fixedDelayString = "${clinicit.notifications.poll-interval:PT10S}")
    public void scheduledRetry() {
        retryDue(clock.instant());
    }

    /** Sends every due PENDING notification. Returns how many were attempted. */
    public int retryDue(Instant now) {
        List<Claimed> claimed = jdbc.query(CLAIM_DUE, Claimed::map,
                Timestamp.from(now.plus(LEASE)), Timestamp.from(now), Timestamp.from(now));
        claimed.forEach(c -> deliver(c, now));
        return claimed.size();
    }

    /** Deletes delivered/failed rows past retention (they contain phone numbers). */
    @Scheduled(initialDelayString = "PT5M", fixedDelayString = "PT6H")
    public void scheduledPurge() {
        purge(clock.instant());
    }

    public int purge(Instant now) {
        return jdbc.update("delete from notifications where status <> 'PENDING' and updated_at < ?",
                Timestamp.from(now.minus(properties.retention())));
    }

    void sendIfDue(UUID id) {
        Instant now = clock.instant();
        List<Claimed> claimed = jdbc.query(CLAIM_ONE, Claimed::map,
                Timestamp.from(now.plus(LEASE)), Timestamp.from(now), id, Timestamp.from(now));
        claimed.forEach(c -> deliver(c, now));
    }

    private void deliver(Claimed claimed, Instant now) {
        if (!claimed.expiresAt.isAfter(now)) {
            fail(claimed, claimed.attempts, "EXPIRED", now);
            return;
        }
        NotificationProvider provider = providers.forChannel(claimed.channel).orElse(null);
        if (provider == null) {
            fail(claimed, claimed.attempts, "NO_PROVIDER_FOR_" + claimed.channel, now);
            return;
        }
        int attempt = claimed.attempts + 1;
        try {
            String providerMessageId = provider.send(new OutboundMessage(
                    claimed.id.toString(), claimed.channel, claimed.recipient, claimed.body));
            jdbc.update("""
                    update notifications set status = 'SENT', attempts = ?, sent_at = ?, updated_at = ?,
                           provider = ?, provider_message_id = ?, last_error = null
                    where id = ? and status = 'PENDING'
                    """, attempt, Timestamp.from(now), Timestamp.from(now), provider.name(),
                    truncate(providerMessageId, 120), claimed.id);
        } catch (NotificationDeliveryException e) {
            retryOrFail(claimed, attempt, e.getCode(), e.isRetryable(), now);
        } catch (RuntimeException e) {
            // Unexpected provider error: retry, and record only the error type (no message text,
            // which could echo the phone number or body).
            log.warn("Provider {} failed for notification {}: {}", provider.name(), claimed.id, e.getClass().getSimpleName());
            retryOrFail(claimed, attempt, "PROVIDER_ERROR:" + e.getClass().getSimpleName(), true, now);
        }
    }

    private void retryOrFail(Claimed claimed, int attempt, String code, boolean retryable, Instant now) {
        if (!retryable || attempt >= claimed.maxAttempts) {
            fail(claimed, attempt, code, now);
            return;
        }
        Instant next = now.plus(backoff(attempt));
        jdbc.update("""
                update notifications set attempts = ?, next_attempt_at = ?, last_error = ?, updated_at = ?
                where id = ? and status = 'PENDING'
                """, attempt, Timestamp.from(next), truncate(code, 300), Timestamp.from(now), claimed.id);
    }

    private void fail(Claimed claimed, int attempts, String code, Instant now) {
        jdbc.update("""
                update notifications set status = 'FAILED', attempts = ?, last_error = ?, updated_at = ?
                where id = ? and status = 'PENDING'
                """, attempts, truncate(code, 300), Timestamp.from(now), claimed.id);
    }

    Duration backoff(int attemptsSoFar) {
        Duration delay = properties.retryBaseDelay().multipliedBy(1L << Math.min(attemptsSoFar - 1, 20));
        return delay.compareTo(properties.retryMaxDelay()) > 0 ? properties.retryMaxDelay() : delay;
    }

    private static String truncate(String value, int max) {
        return value == null || value.length() <= max ? value : value.substring(0, max);
    }

    private record Claimed(UUID id, NotificationChannel channel, String recipient, String body,
                           int attempts, int maxAttempts, Instant expiresAt) {
        static Claimed map(java.sql.ResultSet rs, int row) throws java.sql.SQLException {
            return new Claimed(
                    rs.getObject("id", UUID.class),
                    NotificationChannel.valueOf(rs.getString("channel")),
                    rs.getString("recipient"),
                    rs.getString("body"),
                    rs.getInt("attempts"),
                    rs.getInt("max_attempts"),
                    rs.getTimestamp("expires_at").toInstant());
        }
    }
}
