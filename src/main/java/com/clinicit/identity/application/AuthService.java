package com.clinicit.identity.application;

import com.clinicit.common.domain.AuthenticationFailedException;
import com.clinicit.identity.api.LoginResponse;
import com.clinicit.identity.api.UserResponse;
import com.clinicit.identity.domain.Actor;
import com.clinicit.identity.domain.AuthSession;
import com.clinicit.identity.domain.AuthSessionRepository;
import com.clinicit.identity.domain.UserAccount;
import com.clinicit.identity.domain.UserAccountRepository;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.util.Optional;

@Service
@Transactional
public class AuthService {

    private final UserAccountRepository users;
    private final AuthSessionRepository sessions;
    private final PasswordEncoder passwordEncoder;
    private final SessionTokens tokens;
    private final AuthProperties properties;
    private final LoginThrottle throttle;
    private final Clock clock;

    /** Compared against when the email is unknown, so both failure paths cost one hash check. */
    private final String dummyPasswordHash;

    public AuthService(
            UserAccountRepository users,
            AuthSessionRepository sessions,
            PasswordEncoder passwordEncoder,
            SessionTokens tokens,
            AuthProperties properties,
            LoginThrottle throttle,
            Clock clock
    ) {
        this.users = users;
        this.sessions = sessions;
        this.passwordEncoder = passwordEncoder;
        this.tokens = tokens;
        this.properties = properties;
        this.throttle = throttle;
        this.clock = clock;
        this.dummyPasswordHash = passwordEncoder.encode("timing-equaliser-not-a-real-password");
    }

    /**
     * Unknown email, wrong password, disabled account and a throttled (temporarily locked)
     * account or IP all fail the same way, with the same bcrypt work done, so the response
     * reveals neither which accounts exist nor that a lock is in place.
     */
    public LoginResponse login(String email, String password, String clientIp) {
        String normalizedEmail = UserAccount.normalizeEmail(email);

        // Both checks always run: the account attempt must be counted even if the IP is blocked.
        boolean accountAllowed = throttle.tryAccountAttempt(normalizedEmail);
        boolean ipAllowed = !throttle.isIpBlocked(clientIp);

        Optional<UserAccount> found = users.findByEmail(normalizedEmail);
        boolean passwordMatches = passwordEncoder.matches(
                password, found.map(UserAccount::getPasswordHash).orElse(dummyPasswordHash));

        if (!passwordMatches) {
            throttle.recordIpFailure(clientIp);
        }
        UserAccount user = found
                .filter(account -> accountAllowed && ipAllowed && passwordMatches && account.isEnabled())
                .orElseThrow(AuthenticationFailedException::new);

        throttle.resetAccount(normalizedEmail);
        Instant now = clock.instant();
        String token = tokens.newToken();
        AuthSession session = sessions.save(
                new AuthSession(user.getId(), tokens.hash(token), now, now.plus(properties.sessionTtl())));

        return new LoginResponse(token, "Bearer", session.getExpiresAt(), UserResponse.from(user));
    }

    /** Resolves a bearer token to the staff member it was issued to, if it is still valid. */
    @Transactional(readOnly = true)
    public Optional<Actor> authenticate(String token) {
        Instant now = clock.instant();
        return sessions.findByTokenHash(tokens.hash(token))
                .filter(session -> session.isActiveAt(now))
                .flatMap(session -> users.findById(session.getUserId()))
                .filter(UserAccount::isEnabled)
                .map(UserAccount::toActor);
    }

    public void logout(String token) {
        sessions.findByTokenHash(tokens.hash(token)).ifPresent(session -> session.revoke(clock.instant()));
    }
}
