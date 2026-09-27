package com.clinicit.queue.domain;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.UUID;

/**
 * Issues human-facing queue tokens (#1, #2, ...) per clinic per day.
 *
 * <p>Why not {@code max(token_number) + 1}: two concurrent transactions read the
 * same max and both insert it. Locking the existing rows does not help either:
 * for the first patient of the day there are no rows to lock.
 *
 * <p>Instead one counter row per (clinic, day) is incremented with a single
 * atomic upsert. The row lock taken by the upsert is held until the calling
 * transaction commits, so concurrent joins queue up behind it and each gets
 * the next number. If the join rolls back, the increment rolls back with it,
 * so aborted joins leave no gaps. The unique constraint on
 * (clinic_id, queue_date, token_number) remains as the last line of defence.
 */
@Component
public class QueueTokenAllocator {

    private static final String NEXT_TOKEN_SQL = """
            insert into queue_token_counters (clinic_id, queue_date, last_token)
            values (?, ?, 1)
            on conflict (clinic_id, queue_date)
            do update set last_token = queue_token_counters.last_token + 1
            returning last_token
            """;

    private final JdbcTemplate jdbc;

    public QueueTokenAllocator(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** Must run inside the transaction that creates the queue entry. */
    @Transactional(propagation = Propagation.MANDATORY)
    public int nextToken(UUID clinicId, LocalDate queueDate) {
        Integer token = jdbc.queryForObject(NEXT_TOKEN_SQL, Integer.class, clinicId, queueDate);
        if (token == null) {
            throw new IllegalStateException("Token counter returned no value");
        }
        return token;
    }
}
