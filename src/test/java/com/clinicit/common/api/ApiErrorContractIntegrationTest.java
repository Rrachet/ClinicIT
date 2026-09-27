package com.clinicit.common.api;

import com.clinicit.identity.domain.Role;
import com.clinicit.support.PostgresIntegrationTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Framework-level errors use the same ApiError shape as domain errors. */
class ApiErrorContractIntegrationTest extends PostgresIntegrationTest {

    @Autowired MockMvc mvc;

    String token;

    @BeforeEach
    void signIn() {
        token = login(staff(clinic("City Clinic"), Role.ADMIN, null, "admin@city.test"));
    }

    @Test
    void malformedIdInPathIs400() throws Exception {
        mvc.perform(post("/api/v1/queue-entries/not-a-uuid/start").with(bearer(token)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("BAD_REQUEST"))
                .andExpect(jsonPath("$.path").value("/api/v1/queue-entries/not-a-uuid/start"));
    }

    @Test
    void malformedJsonIs400() throws Exception {
        mvc.perform(post("/api/v1/queue-entries").with(bearer(token)).contentType(MediaType.APPLICATION_JSON).content("{not json"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("BAD_REQUEST"));
    }

    @Test
    void missingRequiredParameterIs400() throws Exception {
        mvc.perform(get("/api/v1/appointments").with(bearer(token)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("BAD_REQUEST"));
    }

    @Test
    void unknownRouteIs404() throws Exception {
        mvc.perform(get("/api/v1/does-not-exist").with(bearer(token)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("NOT_FOUND"));
    }

    @Test
    void wrongMethodIs405() throws Exception {
        mvc.perform(put("/api/v1/queues/call-next").with(bearer(token)))
                .andExpect(status().isMethodNotAllowed())
                .andExpect(jsonPath("$.code").value("METHOD_NOT_ALLOWED"));
    }
}
