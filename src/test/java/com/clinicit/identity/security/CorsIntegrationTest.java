package com.clinicit.identity.security;

import com.clinicit.support.PostgresIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.options;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class CorsIntegrationTest extends PostgresIntegrationTest {

    @Autowired MockMvc mvc;

    @Test
    void preflightFromTheFrontendIsAllowedWithoutCredentials() throws Exception {
        mvc.perform(options("/api/v1/patients")
                        .header("Origin", FRONTEND_ORIGIN)
                        .header("Access-Control-Request-Method", "POST")
                        .header("Access-Control-Request-Headers", "authorization, content-type"))
                .andExpect(status().isOk())
                .andExpect(header().string("Access-Control-Allow-Origin", FRONTEND_ORIGIN))
                .andExpect(header().string("Access-Control-Allow-Methods", containsString("POST")))
                .andExpect(header().doesNotExist("Access-Control-Allow-Credentials"));
    }

    @Test
    void theFrontendMayUsePutAndDeleteForSchedules() throws Exception {
        for (String method : new String[]{"PUT", "DELETE"}) {
            mvc.perform(options("/api/v1/doctors/{id}/schedule", java.util.UUID.randomUUID())
                            .header("Origin", FRONTEND_ORIGIN)
                            .header("Access-Control-Request-Method", method)
                            .header("Access-Control-Request-Headers", "authorization, content-type"))
                    .andExpect(status().isOk())
                    .andExpect(header().string("Access-Control-Allow-Methods", containsString(method)));
        }
        mvc.perform(options("/api/v1/patients")
                        .header("Origin", FRONTEND_ORIGIN)
                        .header("Access-Control-Request-Method", "PATCH"))
                .andExpect(status().isForbidden());
    }

    @Test
    void preflightFromAnUnknownOriginIsRejected() throws Exception {
        mvc.perform(options("/api/v1/patients")
                        .header("Origin", "https://evil.example")
                        .header("Access-Control-Request-Method", "POST"))
                .andExpect(status().isForbidden())
                .andExpect(header().doesNotExist("Access-Control-Allow-Origin"));
    }

    @Test
    void errorResponsesToTheFrontendCarryCorsHeadersSoTheAppCanReadThem() throws Exception {
        mvc.perform(get("/api/v1/doctors").header("Origin", FRONTEND_ORIGIN))
                .andExpect(status().isUnauthorized())
                .andExpect(header().string("Access-Control-Allow-Origin", FRONTEND_ORIGIN))
                .andExpect(header().string("Access-Control-Expose-Headers", containsString("WWW-Authenticate")));
        mvc.perform(get("/api/v1/doctors").header("Origin", "https://evil.example"))
                .andExpect(header().doesNotExist("Access-Control-Allow-Origin"));
    }

    @Test
    void wildcardAndNonOriginValuesAreRefusedAtStartup() {
        assertThatThrownBy(() -> new CorsProperties(List.of("*"))).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new CorsProperties(List.of("https://*.clinicit.app")))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new CorsProperties(List.of("https://app.example.com/path")))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
