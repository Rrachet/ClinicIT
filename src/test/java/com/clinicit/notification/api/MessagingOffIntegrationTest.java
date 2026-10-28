package com.clinicit.notification.api;

import com.clinicit.identity.domain.Role;
import com.clinicit.support.PostgresIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** With patient messaging switched off, reception is told so and hands over the link itself. */
@TestPropertySource(properties = "clinicit.notifications.enabled=false")
class MessagingOffIntegrationTest extends PostgresIntegrationTest {

    @Autowired MockMvc mvc;

    @Test
    void reportsThatPatientsAreNotMessaged() throws Exception {
        String desk = login(staff(clinic("Clinic A"), Role.RECEPTIONIST, null, "desk@a.test"));
        mvc.perform(get("/api/v1/notifications/settings").with(bearer(desk)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.patientMessaging").value(false));
    }
}
