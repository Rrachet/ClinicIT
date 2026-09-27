package com.clinicit.identity.application;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;

/**
 * Deletes sessions that can never authenticate again (expired or revoked) and login
 * throttle rows whose window has passed. Runs in small batches, each in its own short
 * transaction, so a large backlog never holds long locks. Safe to run on several
 * instances at once: the deletes are idempotent.
 */
@Component
public class AuthCleanup {

    private static final Logger log = LoggerFactory.getLogger(AuthCleanup.class);
    private static final int BATCH_SIZE = 1000;

    private static final String DELETE_DEAD_SESSIONS = """
            delete from auth_sessions where id in (
                select id from auth_sessions
                where expires_at <= ? or revoked_at is not null
                limit %d)
            """.formatted(BATCH_SIZE);

    private static final String DELETE_STALE_THROTTLES = """
            delete from login_throttle where throttle_key in (
                select throttle_key from login_throttle
                where window_started_at <= ?
                limit %d)
            """.formatted(BATCH_SIZE);

    private final JdbcTemplate jdbc;
    private final TransactionTemplate tx;
    private final AuthProperties properties;
    private final Clock clock;

    public AuthCleanup(JdbcTemplate jdbc, TransactionTemplate tx, AuthProperties properties, Clock clock) {
        this.jdbc = jdbc;
        this.tx = tx;
        this.properties = properties;
        this.clock = clock;
    }

    @Scheduled(
            initialDelayString = "${clinicit.auth.cleanup-initial-delay:PT1M}",
            fixedDelayString = "${clinicit.auth.cleanup-interval:PT1H}")
    public void scheduledPurge() {
        Result result = purge(clock.instant());
        if (result.sessions() + result.throttles() > 0) {
            log.info("Purged {} dead sessions and {} stale login throttles", result.sessions(), result.throttles());
        }
    }

    public record Result(int sessions, int throttles) {}

    public Result purge(Instant now) {
        int sessions = deleteInBatches(DELETE_DEAD_SESSIONS, Timestamp.from(now));
        int throttles = deleteInBatches(DELETE_STALE_THROTTLES, Timestamp.from(now.minus(properties.loginWindow())));
        return new Result(sessions, throttles);
    }

    private int deleteInBatches(String sql, Timestamp cutoff) {
        int total = 0;
        int deleted;
        do {
            Integer batch = tx.execute(status -> jdbc.update(sql, cutoff));
            deleted = batch == null ? 0 : batch;
            total += deleted;
        } while (deleted == BATCH_SIZE);
        return total;
    }
}
