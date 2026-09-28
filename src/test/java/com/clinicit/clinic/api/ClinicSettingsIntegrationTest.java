package com.clinicit.clinic.api;

import com.clinicit.clinic.domain.Clinic;
import com.clinicit.identity.domain.Role;
import com.clinicit.support.PostgresIntegrationTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Admins can rename their own clinic; nothing else about it, and nobody else. */
class ClinicSettingsIntegrationTest extends PostgresIntegrationTest {

    @Autowired MockMvc mvc;

    Clinic clinic;
    String admin;

    @BeforeEach
    void setUp() {
        clinic = clinic("City Clinic");
        admin = login(staff(clinic, Role.ADMIN, null, "admin@city.test"));
    }

    private org.springframework.test.web.servlet.ResultActions rename(String token, String body) throws Exception {
        return mvc.perform(put("/api/v1/clinic").with(bearer(token))
                .contentType(MediaType.APPLICATION_JSON).content(body));
    }

    @Test
    void anAdminRenamesTheirClinicAndTheTimezoneStays() throws Exception {
        rename(admin, "{\"name\": \"  ClinicIT Demo Clinic, Hyderabad \", \"timezone\": \"UTC\"}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("ClinicIT Demo Clinic, Hyderabad"))
                .andExpect(jsonPath("$.timezone").value("Asia/Kolkata"));
        mvc.perform(get("/api/v1/clinic").with(bearer(admin)))
                .andExpect(jsonPath("$.name").value("ClinicIT Demo Clinic, Hyderabad"));
    }

    @Test
    void onlyAdminsMayRenameAndOnlyTheirOwnClinic() throws Exception {
        String desk = login(staff(clinic, Role.RECEPTIONIST, null, "desk@city.test"));
        rename(desk, "{\"name\": \"Hijacked\"}").andExpect(status().isForbidden());

        Clinic other = clinic("Other Clinic");
        String otherAdmin = login(staff(other, Role.ADMIN, null, "admin@other.test"));
        rename(otherAdmin, "{\"name\": \"Renamed Other\"}").andExpect(status().isOk());
        mvc.perform(get("/api/v1/clinic").with(bearer(admin))).andExpect(jsonPath("$.name").value("City Clinic"));
    }

    @Test
    void aNameIsRequiredAndBounded() throws Exception {
        rename(admin, "{\"name\": \"   \"}").andExpect(status().isBadRequest());
        rename(admin, "{\"name\": \"" + "x".repeat(151) + "\"}").andExpect(status().isBadRequest());
    }
}
