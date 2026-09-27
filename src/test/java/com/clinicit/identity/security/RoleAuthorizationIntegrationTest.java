package com.clinicit.identity.security;

import com.clinicit.appointment.domain.Appointment;
import com.clinicit.clinic.domain.Clinic;
import com.clinicit.clinic.domain.DoctorProfile;
import com.clinicit.identity.domain.Role;
import com.clinicit.queue.api.QueueEntryResponse;
import com.clinicit.queue.application.QueueService;
import com.clinicit.support.PostgresIntegrationTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Role rules within one clinic (cross-clinic access is ClinicIsolationIntegrationTest). */
class RoleAuthorizationIntegrationTest extends PostgresIntegrationTest {

    @Autowired MockMvc mvc;
    @Autowired QueueService queue;

    Clinic clinic;
    DoctorProfile sharma;
    DoctorProfile mehta;
    String admin;
    String receptionist;
    String drSharma;

    @BeforeEach
    void setUp() {
        clinic = clinic("City Clinic");
        sharma = doctor(clinic, "Dr. Sharma");
        mehta = doctor(clinic, "Dr. Mehta");
        admin = login(staff(clinic, Role.ADMIN, null, "admin@city.test"));
        receptionist = login(staff(clinic, Role.RECEPTIONIST, null, "desk@city.test"));
        drSharma = login(staff(clinic, Role.DOCTOR, sharma, "sharma@city.test"));
    }

    private static MockHttpServletRequestBuilder json(MockHttpServletRequestBuilder request, String body) {
        return request.contentType(MediaType.APPLICATION_JSON).content(body);
    }

    private QueueEntryResponse queued(DoctorProfile doctor) {
        return queue.join(frontDesk(clinic), arrivedAppointment(doctor, patient(clinic, "P")).getId());
    }

    @Test
    void doctorsCannotDoFrontDeskOrAdminWork() throws Exception {
        Appointment booked = appointmentOn(sharma, patient(clinic, "Rahul"), TODAY);
        for (MockHttpServletRequestBuilder request : new MockHttpServletRequestBuilder[]{
                json(post("/api/v1/patients"), "{\"fullName\":\"X\",\"phone\":\"1\"}"),
                get("/api/v1/patients").param("name", "R"),
                json(post("/api/v1/appointments"), "{\"patientId\":\"%s\",\"doctorId\":\"%s\",\"scheduledAt\":\"%sT17:00:00\"}"
                        .formatted(booked.getPatientId(), sharma.getId(), TODAY)),
                post("/api/v1/appointments/{id}/confirm", booked.getId()),
                json(post("/api/v1/queue-entries"), "{\"appointmentId\":\"%s\"}".formatted(booked.getId())),
                json(post("/api/v1/doctors"), "{\"displayName\":\"Dr. X\"}"),
                get("/api/v1/users")}) {
            mvc.perform(request.with(bearer(drSharma)))
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.code").value("FORBIDDEN"));
        }
    }

    @Test
    void receptionistsCannotManageStaffOrDoctors() throws Exception {
        mvc.perform(get("/api/v1/users").with(bearer(receptionist))).andExpect(status().isForbidden());
        mvc.perform(json(post("/api/v1/doctors"), "{\"displayName\":\"Dr. X\"}").with(bearer(receptionist)))
                .andExpect(status().isForbidden());
        mvc.perform(json(post("/api/v1/users"), """
                        {"email":"x@city.test","fullName":"X","password":"%s","role":"ADMIN"}""".formatted(PASSWORD))
                        .with(bearer(receptionist)))
                .andExpect(status().isForbidden());
    }

    @Test
    void adminCanDoFrontDeskWork() throws Exception {
        mvc.perform(json(post("/api/v1/patients"), "{\"fullName\":\"X\",\"phone\":\"1\"}").with(bearer(admin)))
                .andExpect(status().isCreated());
        mvc.perform(json(post("/api/v1/doctors"), "{\"displayName\":\"Dr. New\"}").with(bearer(admin)))
                .andExpect(status().isCreated());
    }

    @Test
    void doctorRunsOwnQueueWithoutNamingThemselves() throws Exception {
        QueueEntryResponse entry = queued(sharma);

        mvc.perform(get("/api/v1/queues/today").with(bearer(drSharma)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.doctorId").value(sharma.getId().toString()));
        mvc.perform(post("/api/v1/queues/call-next").with(bearer(drSharma)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(entry.id().toString()));
        mvc.perform(post("/api/v1/queue-entries/{id}/start", entry.id()).with(bearer(drSharma)))
                .andExpect(status().isOk());
        mvc.perform(post("/api/v1/queue-entries/{id}/complete", entry.id()).with(bearer(drSharma)))
                .andExpect(jsonPath("$.status").value("COMPLETED"));
    }

    @Test
    void doctorCannotTouchAColleaguesQueueOrAppointments() throws Exception {
        QueueEntryResponse colleaguesEntry = queued(mehta);
        String mehtaId = mehta.getId().toString();

        for (MockHttpServletRequestBuilder request : new MockHttpServletRequestBuilder[]{
                get("/api/v1/queues/today").param("doctorId", mehtaId),
                json(post("/api/v1/queues/call-next"), "{\"doctorId\":\"%s\"}".formatted(mehtaId)),
                get("/api/v1/queue-entries/{id}", colleaguesEntry.id()),
                post("/api/v1/queue-entries/{id}/skip", colleaguesEntry.id()),
                get("/api/v1/appointments/{id}", colleaguesEntry.appointmentId()),
                get("/api/v1/appointments").param("date", TODAY.toString()).param("doctorId", mehtaId)}) {
            mvc.perform(request.with(bearer(drSharma))).andExpect(status().isForbidden());
        }
    }

    @Test
    void doctorsAppointmentListIsAlwaysTheirOwn() throws Exception {
        Appointment own = appointmentOn(sharma, patient(clinic, "Own"), TODAY);
        appointmentOn(mehta, patient(clinic, "Colleague's"), TODAY);

        mvc.perform(get("/api/v1/appointments").param("date", TODAY.toString()).with(bearer(drSharma)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].id").value(own.getId().toString()));
        mvc.perform(get("/api/v1/appointments").param("date", TODAY.toString()).with(bearer(receptionist)))
                .andExpect(jsonPath("$.length()").value(2));
    }

    @Test
    void frontDeskMustNameTheDoctorForQueueOperations() throws Exception {
        mvc.perform(post("/api/v1/queues/call-next").with(bearer(receptionist)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
    }
}
