package com.clinicit.identity.api;

import com.clinicit.clinic.domain.Clinic;
import com.clinicit.common.domain.AuthenticationFailedException;
import com.clinicit.identity.domain.Role;
import com.clinicit.support.PostgresIntegrationTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

class LoginThrottleIntegrationTest extends PostgresIntegrationTest {

    private static final String WRONG = "definitely not the password";

    @Autowired MockMvc mvc;

    @BeforeEach
    void setUp() {
        Clinic clinic = clinic("City Clinic");
        staff(clinic, Role.RECEPTIONIST, null, "desk@city.test");
        staff(clinic, Role.RECEPTIONIST, null, "other@city.test");
    }

    private MockHttpServletResponse attempt(String email, String password, String ip) throws Exception {
        return mvc.perform(post("/api/v1/auth/login")
                        .with(request -> { request.setRemoteAddr(ip); return request; })
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"%s\",\"password\":\"%s\"}".formatted(email, password)))
                .andReturn().getResponse();
    }

    /** The response body without the per-request timestamp. */
    private static String failureShape(MockHttpServletResponse response) throws Exception {
        return response.getStatus() + " " + response.getContentAsString().replaceAll("\"timestamp\":\"[^\"]+\",", "");
    }

    @Test
    void accountLocksAfterFiveAttemptsWithTheSameGenericFailure() throws Exception {
        String ordinaryFailure = failureShape(attempt("desk@city.test", WRONG, "10.0.0.1"));
        for (int i = 0; i < 4; i++) {
            attempt("desk@city.test", WRONG, "10.0.0.1");
        }

        // Correct password, but the account is locked: indistinguishable from a wrong password.
        MockHttpServletResponse locked = attempt("desk@city.test", PASSWORD, "10.0.0.2");
        assertThat(failureShape(locked)).isEqualTo(ordinaryFailure);
        assertThat(locked.getHeader("Retry-After")).isNull();

        // Other accounts are unaffected.
        assertThat(attempt("other@city.test", PASSWORD, "10.0.0.1").getStatus()).isEqualTo(200);

        // The lock is temporary.
        clock.set(NOW.plus(Duration.ofMinutes(15)));
        assertThat(attempt("desk@city.test", PASSWORD, "10.0.0.2").getStatus()).isEqualTo(200);
    }

    @Test
    void successfulLoginResetsTheAccountCounter() throws Exception {
        for (int round = 0; round < 3; round++) {
            for (int i = 0; i < 4; i++) {
                attempt("desk@city.test", WRONG, "10.0.0.1");
            }
            assertThat(attempt("desk@city.test", PASSWORD, "10.0.0.1").getStatus()).isEqualTo(200);
        }
    }

    @Test
    void unknownEmailsAreThrottledTooSoLockingRevealsNothing() throws Exception {
        String ordinaryFailure = failureShape(attempt("ghost@city.test", WRONG, "10.0.0.1"));
        for (int i = 0; i < 10; i++) {
            assertThat(failureShape(attempt("ghost@city.test", WRONG, "10.0.0.1"))).isEqualTo(ordinaryFailure);
        }
    }

    @Test
    void oneIpSprayingManyAccountsIsBlocked() throws Exception {
        for (int i = 0; i < 30; i++) {
            attempt("victim" + i + "@city.test", WRONG, "203.0.113.9");
        }

        assertThat(attempt("desk@city.test", PASSWORD, "203.0.113.9").getStatus()).isEqualTo(401);
        assertThat(attempt("desk@city.test", PASSWORD, "198.51.100.7").getStatus()).isEqualTo(200);
    }

    @Test
    void manySuccessfulLoginsFromOneIpAreNotThrottled() throws Exception {
        // A clinic's staff behind one NAT: only failures count per IP.
        for (int i = 0; i < 40; i++) {
            assertThat(attempt(i % 2 == 0 ? "desk@city.test" : "other@city.test", PASSWORD, "10.1.1.1").getStatus())
                    .isEqualTo(200);
        }
    }

    @Test
    void parallelGuessesCannotSlipPastTheLimit() throws Exception {
        int guesses = 20;
        ExecutorService pool = Executors.newFixedThreadPool(guesses);
        CountDownLatch go = new CountDownLatch(1);
        List<Future<Boolean>> results = new ArrayList<>();
        for (int i = 0; i < guesses; i++) {
            results.add(pool.submit(() -> {
                go.await();
                try {
                    authService.login("desk@city.test", WRONG, "10.0.0.1");
                    return true;
                } catch (AuthenticationFailedException expected) {
                    return false;
                }
            }));
        }
        go.countDown();
        for (Future<Boolean> result : results) {
            assertThat(result.get(30, TimeUnit.SECONDS)).isFalse();
        }
        pool.shutdown();

        // Every attempt was counted atomically (no lost increments), so the account is locked.
        assertThat(jdbc.queryForObject("select max(attempts) from login_throttle", Integer.class)).isEqualTo(guesses);
        assertThat(attempt("desk@city.test", PASSWORD, "10.0.0.3").getStatus()).isEqualTo(401);
    }

    @Test
    void throttleKeysDoNotStoreEmailsOrAddresses() throws Exception {
        attempt("desk@city.test", WRONG, "10.0.0.1");

        assertThat(jdbc.queryForList("select throttle_key from login_throttle", String.class))
                .isNotEmpty()
                .allSatisfy(key -> assertThat(key).hasSize(64).doesNotContain("desk").doesNotContain("10.0.0.1"));
    }
}
