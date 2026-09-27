package com.clinicit.identity.application;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;

/**
 * Brute-force protection for password checks, backed by PostgreSQL (no extra
 * infrastructure; works across several app instances because the counters live in
 * the shared database).
 *
 * <p>Two limits:
 * <ul>
 *   <li><b>Per account</b>: attempts are <i>reserved before</i> the password is checked,
 *       with an atomic upsert, so a burst of parallel guesses cannot all slip past the
 *       limit. A successful login resets the counter.</li>
 *   <li><b>Per client IP</b>: counts failures only, so one address cannot spray guesses
 *       across many accounts, while a clinic with many staff behind one NAT can still
 *       log in normally.</li>
 * </ul>
 *
 * <p>Every method runs in its own transaction ({@code REQUIRES_NEW}): a failed login
 * rolls back the caller's transaction, and the counters must survive that.
 */
@Component
public class LoginThrottle {

    private static final String INCREMENT_SQL = """
            insert into login_throttle (throttle_key, attempts, window_started_at)
            values (?, 1, ?)
            on conflict (throttle_key) do update set
                attempts = case when login_throttle.window_started_at <= ? then 1
                                else login_throttle.attempts + 1 end,
                window_started_at = case when login_throttle.window_started_at <= ? then excluded.window_started_at
                                         else login_throttle.window_started_at end
            returning attempts
            """;

    private final JdbcTemplate jdbc;
    private final SessionTokens hashing;
    private final AuthProperties properties;
    private final Clock clock;

    public LoginThrottle(JdbcTemplate jdbc, SessionTokens hashing, AuthProperties properties, Clock clock) {
        this.jdbc = jdbc;
        this.hashing = hashing;
        this.properties = properties;
        this.clock = clock;
    }

    /** Counts one attempt for this account; false once the account has used up its window. */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public boolean tryAccountAttempt(String normalizedEmail) {
        return increment(accountKey(normalizedEmail)) <= properties.maxAttemptsPerAccount();
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void resetAccount(String normalizedEmail) {
        jdbc.update("delete from login_throttle where throttle_key = ?", accountKey(normalizedEmail));
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW, readOnly = true)
    public boolean isIpBlocked(String clientIp) {
        Integer failures = jdbc.query(
                "select attempts from login_throttle where throttle_key = ? and window_started_at > ?",
                rs -> rs.next() ? rs.getInt(1) : 0,
                ipKey(clientIp), Timestamp.from(windowStart()));
        return failures != null && failures >= properties.maxFailuresPerIp();
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void recordIpFailure(String clientIp) {
        increment(ipKey(clientIp));
    }

    private int increment(String key) {
        Timestamp now = Timestamp.from(clock.instant());
        Timestamp expired = Timestamp.from(windowStart());
        Integer attempts = jdbc.queryForObject(INCREMENT_SQL, Integer.class, key, now, expired, expired);
        return attempts == null ? Integer.MAX_VALUE : attempts;
    }

    private Instant windowStart() {
        return clock.instant().minus(properties.loginWindow());
    }

    private String accountKey(String normalizedEmail) {
        return hashing.hash("account:" + normalizedEmail);
    }

    private String ipKey(String clientIp) {
        return hashing.hash("ip:" + (clientIp == null ? "unknown" : clientIp));
    }
}
