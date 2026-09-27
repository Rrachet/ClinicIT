package com.clinicit.common.api;

import com.clinicit.support.PostgresIntegrationTest;
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

    @Test
    void malformedIdInPathIs400() throws Exception {
        mvc.perform(post("/api/v1/queue-entries/not-a-uuid/start"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("BAD_REQUEST"))
                .andExpect(jsonPath("$.path").value("/api/v1/queue-entries/not-a-uuid/start"));
    }

    @Test
    void malformedJsonIs400() throws Exception {
        mvc.perform(post("/api/v1/queue-entries").contentType(MediaType.APPLICATION_JSON).content("{not json"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("BAD_REQUEST"));
    }

    @Test
    void missingRequiredParameterIs400() throws Exception {
        mvc.perform(get("/api/v1/queues/today"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("BAD_REQUEST"));
    }

    @Test
    void unknownRouteIs404() throws Exception {
        mvc.perform(get("/api/v1/does-not-exist"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("NOT_FOUND"));
    }

    @Test
    void wrongMethodIs405() throws Exception {
        mvc.perform(put("/api/v1/queues/call-next"))
                .andExpect(status().isMethodNotAllowed())
                .andExpect(jsonPath("$.code").value("METHOD_NOT_ALLOWED"));
    }
}
