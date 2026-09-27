package com.clinicit.notification.api;

import com.clinicit.appointment.domain.Appointment;
import com.clinicit.clinic.domain.Clinic;
import com.clinicit.clinic.domain.DoctorProfile;
import com.clinicit.identity.domain.Role;
import com.clinicit.notification.application.NotificationDispatcher;
import com.clinicit.notification.infrastructure.DevelopmentNotificationProvider;
import com.clinicit.queue.application.QueueService;
import com.clinicit.support.PostgresIntegrationTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Duration;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Front desk can see and retry their own clinic's messages, and nobody else's. */
class NotificationApiIntegrationTest extends PostgresIntegrationTest {

    @Autowired MockMvc mvc;
    @Autowired QueueService queue;
    @Autowired NotificationDispatcher dispatcher;
    @Autowired DevelopmentNotificationProvider provider;

    Clinic clinicA;
    DoctorProfile doctorA;
    Appointment appointmentA;
    UUID notificationA;
    String deskA;
    String doctorTokenA;
    String deskB;

    @BeforeEach
    void setUp() {
        provider.reset();
        clinicA = clinic("Clinic A");
        doctorA = doctor(clinicA, "Dr. A");
        deskA = login(staff(clinicA, Role.RECEPTIONIST, null, "desk@a.test"));
        doctorTokenA = login(staff(clinicA, Role.DOCTOR, doctorA, "doctor@a.test"));
        deskB = login(staff(clinic("Clinic B"), Role.RECEPTIONIST, null, "desk@b.test"));

        provider.failNextSends(100);
        appointmentA = arrivedAppointment(doctorA, patient(clinicA, "Asha Rao"));
        queue.join(frontDesk(clinicA), appointmentA.getId());
        notificationA = jdbc.queryForObject("select id from notifications", UUID.class);
    }

    @Test
    void frontDeskSeesTheMessagesWithTheNumberMasked() throws Exception {
        mvc.perform(get("/api/v1/notifications").param("appointmentId", appointmentA.getId().toString()).with(bearer(deskA)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].type").value("PATIENT_JOINED_QUEUE"))
                .andExpect(jsonPath("$[0].status").value("PENDING"))
                .andExpect(jsonPath("$[0].recipient").value("•••0000"))
                .andExpect(content().string(not(containsString("90000 00000"))))
                .andExpect(content().string(not(containsString("Asha"))));
    }

    @Test
    void aFailedMessageCanBeRetriedByTheFrontDesk() throws Exception {
        for (int i = 1; i <= 6; i++) {
            dispatcher.retryDue(NOW.plus(Duration.ofHours(i)));
        }
        assertThat(jdbc.queryForObject("select status from notifications", String.class)).isEqualTo("FAILED");
        provider.reset();

        mvc.perform(post("/api/v1/notifications/{id}/retry", notificationA).with(bearer(deskA)))
                .andExpect(status().isOk());

        assertThat(jdbc.queryForObject("select status from notifications", String.class)).isEqualTo("SENT");
        assertThat(provider.delivered()).hasSize(1);
        mvc.perform(post("/api/v1/notifications/{id}/retry", notificationA).with(bearer(deskA)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("NOTIFICATION_NOT_FAILED"));
    }

    @Test
    void anExpiredMessageCannotBeRetried() throws Exception {
        jdbc.update("update notifications set status = 'FAILED', expires_at = ? where id = ?",
                java.sql.Timestamp.from(NOW.minusSeconds(1)), notificationA);

        mvc.perform(post("/api/v1/notifications/{id}/retry", notificationA).with(bearer(deskA)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("NOTIFICATION_EXPIRED"));
    }

    @Test
    void anotherClinicSeesNothingAndCannotRetry() throws Exception {
        jdbc.update("update notifications set status = 'FAILED' where id = ?", notificationA);

        mvc.perform(get("/api/v1/notifications").param("appointmentId", appointmentA.getId().toString()).with(bearer(deskB)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(0));
        mvc.perform(post("/api/v1/notifications/{id}/retry", notificationA).with(bearer(deskB)))
                .andExpect(status().isNotFound());
        assertThat(jdbc.queryForObject("select status from notifications", String.class)).isEqualTo("FAILED");
    }

    @Test
    void doctorsAndAnonymousCallersHaveNoAccess() throws Exception {
        mvc.perform(get("/api/v1/notifications").param("appointmentId", appointmentA.getId().toString()).with(bearer(doctorTokenA)))
                .andExpect(status().isForbidden());
        mvc.perform(post("/api/v1/notifications/{id}/retry", notificationA).with(bearer(doctorTokenA)))
                .andExpect(status().isForbidden());
        mvc.perform(get("/api/v1/notifications").param("appointmentId", appointmentA.getId().toString()))
                .andExpect(status().isUnauthorized());
    }
}
