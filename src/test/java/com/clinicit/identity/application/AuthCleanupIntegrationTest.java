package com.clinicit.identity.application;

import com.clinicit.identity.domain.Role;
import com.clinicit.identity.domain.UserAccount;
import com.clinicit.support.PostgresIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class AuthCleanupIntegrationTest extends PostgresIntegrationTest {

    @Autowired AuthCleanup cleanup;
    @Autowired MockMvc mvc;

    @Test
    void purgesOnlySessionsThatCanNeverAuthenticateAgain() throws Exception {
        UserAccount user = staff(clinic("City Clinic"), Role.RECEPTIONIST, null, "desk@city.test");

        String expiring = login(user);                 // issued at NOW, expires NOW+12h
        clock.set(NOW.plus(Duration.ofHours(6)));
        String revoked = login(user);
        authService.logout(revoked);
        String active = login(user);                   // expires NOW+18h

        clock.set(NOW.plus(Duration.ofHours(13)));     // 'expiring' is now past its expiry
        mvc.perform(get("/api/v1/auth/me").with(bearer(expiring))).andExpect(status().isUnauthorized());

        AuthCleanup.Result result = cleanup.purge(clock.instant());

        assertThat(result.sessions()).isEqualTo(2);
        assertThat(jdbc.queryForObject("select count(*) from auth_sessions", Integer.class)).isEqualTo(1);
        mvc.perform(get("/api/v1/auth/me").with(bearer(active))).andExpect(status().isOk());
    }

    @Test
    void purgesThrottleRowsOnceTheirWindowHasPassed() {
        staff(clinic("City Clinic"), Role.RECEPTIONIST, null, "desk@city.test");
        try {
            authService.login("desk@city.test", "wrong password here", "10.0.0.1");
        } catch (RuntimeException expected) {
            // recorded in the throttle table
        }
        assertThat(cleanup.purge(clock.instant()).throttles()).isZero();

        assertThat(cleanup.purge(clock.instant().plus(Duration.ofMinutes(16))).throttles()).isEqualTo(2);
        assertThat(jdbc.queryForObject("select count(*) from login_throttle", Integer.class)).isZero();
    }
}
