package com.clinicit.identity.api;

import com.clinicit.clinic.domain.Clinic;
import com.clinicit.clinic.domain.DoctorProfile;
import com.clinicit.identity.domain.Role;
import com.clinicit.identity.domain.UserAccount;
import com.clinicit.support.PostgresIntegrationTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class UserManagementIntegrationTest extends PostgresIntegrationTest {

    @Autowired MockMvc mvc;

    Clinic clinic;
    DoctorProfile sharma;
    UserAccount adminUser;
    String admin;

    @BeforeEach
    void setUp() {
        clinic = clinic("City Clinic");
        sharma = doctor(clinic, "Dr. Sharma");
        adminUser = staff(clinic, Role.ADMIN, null, "admin@city.test");
        admin = login(adminUser);
    }

    private ResultActions createUser(String body) throws Exception {
        return mvc.perform(post("/api/v1/users").with(bearer(admin))
                .contentType(MediaType.APPLICATION_JSON).content(body));
    }

    @Test
    void adminCreatesStaffWhoCanThenLogIn() throws Exception {
        createUser("""
                {"email":"New.Desk@City.test","fullName":"New Desk","password":"%s","role":"RECEPTIONIST"}"""
                .formatted(PASSWORD))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.email").value("new.desk@city.test"))
                .andExpect(jsonPath("$.clinicId").value(clinic.getId().toString()))
                .andExpect(jsonPath("$.password").doesNotExist());

        authService.login("new.desk@city.test", PASSWORD);
    }

    @Test
    void doctorAccountMustBeLinkedToOneDoctorProfile() throws Exception {
        createUser("""
                {"email":"doc@city.test","fullName":"Doc","password":"%s","role":"DOCTOR"}""".formatted(PASSWORD))
                .andExpect(status().isBadRequest());
        createUser("""
                {"email":"desk2@city.test","fullName":"Desk","password":"%s","role":"RECEPTIONIST","doctorProfileId":"%s"}"""
                .formatted(PASSWORD, sharma.getId()))
                .andExpect(status().isBadRequest());

        String linked = """
                {"email":"%s","fullName":"Dr. Sharma","password":"%s","role":"DOCTOR","doctorProfileId":"%s"}""";
        createUser(linked.formatted("sharma@city.test", PASSWORD, sharma.getId()))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.doctorProfileId").value(sharma.getId().toString()));
        createUser(linked.formatted("sharma2@city.test", PASSWORD, sharma.getId()))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("DOCTOR_ALREADY_LINKED"));
    }

    @Test
    void duplicateEmailAndWeakPasswordAreRejected() throws Exception {
        createUser("""
                {"email":"ADMIN@city.test","fullName":"Dup","password":"%s","role":"ADMIN"}""".formatted(PASSWORD))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("EMAIL_TAKEN"));
        createUser("""
                {"email":"short@city.test","fullName":"Short","password":"short","role":"ADMIN"}""")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
    }

    @Test
    void disablingAUserRevokesTheirTokensImmediately() throws Exception {
        UserAccount desk = staff(clinic, Role.RECEPTIONIST, null, "desk@city.test");
        String token = login(desk);
        mvc.perform(get("/api/v1/doctors").with(bearer(token))).andExpect(status().isOk());

        mvc.perform(post("/api/v1/users/{id}/disable", desk.getId()).with(bearer(admin)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.enabled").value(false));

        mvc.perform(get("/api/v1/doctors").with(bearer(token))).andExpect(status().isUnauthorized());
    }

    @Test
    void adminCannotLockThemselvesOut() throws Exception {
        mvc.perform(post("/api/v1/users/{id}/disable", adminUser.getId()).with(bearer(admin)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("CANNOT_DISABLE_SELF"));
    }

    @Test
    void databaseRejectsDoctorLinkedToAnotherClinicsProfile() {
        DoctorProfile foreign = doctor(clinic("Other Clinic"), "Dr. Foreign");

        org.assertj.core.api.Assertions.assertThatThrownBy(() ->
                        staff(clinic, Role.DOCTOR, foreign, "cross@city.test"))
                .isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
    }
}
