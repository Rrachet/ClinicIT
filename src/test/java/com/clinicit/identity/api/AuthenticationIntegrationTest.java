package com.clinicit.identity.api;

import com.clinicit.clinic.domain.Clinic;
import com.clinicit.identity.domain.Role;
import com.clinicit.identity.domain.UserAccount;
import com.clinicit.support.PostgresIntegrationTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.startsWith;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class AuthenticationIntegrationTest extends PostgresIntegrationTest {

    @Autowired MockMvc mvc;

    Clinic clinic;
    UserAccount receptionist;

    @BeforeEach
    void setUp() {
        clinic = clinic("City Clinic");
        receptionist = staff(clinic, Role.RECEPTIONIST, null, "desk@city.test");
    }

    private ResultActions login(String email, String password) throws Exception {
        return mvc.perform(post("/api/v1/auth/login").contentType(MediaType.APPLICATION_JSON)
                .content("{\"email\":\"%s\",\"password\":\"%s\"}".formatted(email, password)));
    }

    @Test
    void loginReturnsBearerTokenAndIdentity() throws Exception {
        login("desk@city.test", PASSWORD)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.tokenType").value("Bearer"))
                .andExpect(jsonPath("$.accessToken").isString())
                .andExpect(jsonPath("$.expiresAt").value(NOW.plus(Duration.ofHours(12)).toString()))
                .andExpect(jsonPath("$.user.role").value("RECEPTIONIST"))
                .andExpect(jsonPath("$.user.clinicId").value(clinic.getId().toString()))
                .andExpect(jsonPath("$.user.passwordHash").doesNotExist());
    }

    @Test
    void emailIsCaseInsensitive() throws Exception {
        login("  Desk@CITY.test ", PASSWORD).andExpect(status().isOk());
    }

    @Test
    void wrongPasswordUnknownEmailAndDisabledAccountFailIdentically() throws Exception {
        UserAccount disabled = staff(clinic, Role.RECEPTIONIST, null, "gone@city.test");
        disabled.disable();
        users.save(disabled);

        for (String[] attempt : new String[][]{
                {"desk@city.test", "wrong password here"},
                {"nobody@city.test", PASSWORD},
                {"gone@city.test", PASSWORD}}) {
            login(attempt[0], attempt[1])
                    .andExpect(status().isUnauthorized())
                    .andExpect(jsonPath("$.code").value("INVALID_CREDENTIALS"))
                    .andExpect(jsonPath("$.message").value("Invalid email or password"));
        }
    }

    @Test
    void passwordsAreStoredAsBcryptAndTokensOnlyAsHashes() {
        String token = login(receptionist);

        String hash = jdbc.queryForObject("select password_hash from users where id = ?", String.class, receptionist.getId());
        assertThat(hash).startsWith("{bcrypt}$2").doesNotContain(PASSWORD);

        String storedToken = jdbc.queryForObject("select token_hash from auth_sessions", String.class);
        assertThat(storedToken).hasSize(64).isNotEqualTo(token);
        assertThat(token.length()).isGreaterThanOrEqualTo(43); // 256 random bits
    }

    @Test
    void protectedEndpointWithoutTokenIs401WithBearerChallenge() throws Exception {
        mvc.perform(get("/api/v1/doctors"))
                .andExpect(status().isUnauthorized())
                .andExpect(header().string("WWW-Authenticate", startsWith("Bearer")))
                .andExpect(jsonPath("$.code").value("UNAUTHORIZED"));
    }

    @Test
    void garbageTokenIs401() throws Exception {
        mvc.perform(get("/api/v1/doctors").with(bearer("not-a-real-token")))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHORIZED"));
    }

    @Test
    void meReturnsTheAuthenticatedUser() throws Exception {
        mvc.perform(get("/api/v1/auth/me").with(bearer(login(receptionist))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.email").value("desk@city.test"))
                .andExpect(jsonPath("$.role").value("RECEPTIONIST"));
    }

    @Test
    void tokenExpiresAfterSessionTtl() throws Exception {
        String token = login(receptionist);
        mvc.perform(get("/api/v1/doctors").with(bearer(token))).andExpect(status().isOk());

        clock.set(NOW.plus(Duration.ofHours(12)));

        mvc.perform(get("/api/v1/doctors").with(bearer(token))).andExpect(status().isUnauthorized());
    }

    @Test
    void logoutRevokesOnlyThatToken() throws Exception {
        String phone = login(receptionist);
        String desktop = login(receptionist);

        mvc.perform(post("/api/v1/auth/logout").with(bearer(phone))).andExpect(status().isNoContent());

        mvc.perform(get("/api/v1/doctors").with(bearer(phone))).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/v1/doctors").with(bearer(desktop))).andExpect(status().isOk());
    }

    @Test
    void healthAndLoginArePublic() throws Exception {
        mvc.perform(get("/api/v1/health")).andExpect(status().isOk());
        login("desk@city.test", PASSWORD).andExpect(status().isOk());
    }
}
