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

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class PasswordChangeIntegrationTest extends PostgresIntegrationTest {

    private static final String NEW_PASSWORD = "a brand new long passphrase";

    @Autowired MockMvc mvc;

    UserAccount user;

    @BeforeEach
    void setUp() {
        Clinic clinic = clinic("City Clinic");
        user = staff(clinic, Role.DOCTOR, doctor(clinic, "Dr. Sharma"), "sharma@city.test");
    }

    private ResultActions change(String token, String current, String next) throws Exception {
        return mvc.perform(post("/api/v1/auth/password").with(bearer(token))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"currentPassword\":\"%s\",\"newPassword\":\"%s\"}".formatted(current, next)));
    }

    @Test
    void changingPasswordRevokesEverySessionAndSwapsTheCredential() throws Exception {
        String thisDevice = login(user);
        String otherDevice = login(user);

        change(thisDevice, PASSWORD, NEW_PASSWORD).andExpect(status().isNoContent());

        mvc.perform(get("/api/v1/auth/me").with(bearer(thisDevice))).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/v1/auth/me").with(bearer(otherDevice))).andExpect(status().isUnauthorized());
        assertThatThrownBy(() -> authService.login("sharma@city.test", PASSWORD, "127.0.0.1"))
                .isInstanceOf(com.clinicit.common.domain.AuthenticationFailedException.class);
        String fresh = authService.login("sharma@city.test", NEW_PASSWORD, "127.0.0.1").accessToken();
        mvc.perform(get("/api/v1/auth/me").with(bearer(fresh))).andExpect(status().isOk());
    }

    @Test
    void wrongCurrentPasswordChangesNothing() throws Exception {
        String token = login(user);

        change(token, "not my password at all", NEW_PASSWORD)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_CURRENT_PASSWORD"));

        mvc.perform(get("/api/v1/auth/me").with(bearer(token))).andExpect(status().isOk());
        authService.login("sharma@city.test", PASSWORD, "127.0.0.1");
    }

    @Test
    void stolenTokenCannotBeUsedToBruteForceTheCurrentPassword() throws Exception {
        String token = login(user);
        for (int i = 0; i < 5; i++) {
            change(token, "guess number " + i + " here", NEW_PASSWORD).andExpect(status().isBadRequest());
        }

        change(token, PASSWORD, NEW_PASSWORD)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_CURRENT_PASSWORD"));
    }

    @Test
    void newPasswordMustBeLongEnoughAndDifferent() throws Exception {
        String token = login(user);

        change(token, PASSWORD, "short").andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
        change(token, PASSWORD, PASSWORD).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("PASSWORD_UNCHANGED"));
    }

    @Test
    void requiresAuthentication() throws Exception {
        mvc.perform(post("/api/v1/auth/password").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"currentPassword\":\"x\",\"newPassword\":\"%s\"}".formatted(NEW_PASSWORD)))
                .andExpect(status().isUnauthorized());
    }
}
