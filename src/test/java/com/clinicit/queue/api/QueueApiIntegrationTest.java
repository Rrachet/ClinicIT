package com.clinicit.queue.api;

import com.clinicit.appointment.domain.Appointment;
import com.clinicit.clinic.domain.Clinic;
import com.clinicit.clinic.domain.DoctorProfile;
import com.clinicit.support.PostgresIntegrationTest;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.util.UUID;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** The receptionist → doctor flow over HTTP, including the error contract. */
class QueueApiIntegrationTest extends PostgresIntegrationTest {

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;

    Clinic clinic;
    DoctorProfile doctor;

    @BeforeEach
    void setUp() {
        clinic = clinic("City Clinic");
        doctor = doctor(clinic, "Dr. Sharma");
    }

    private JsonNode postJson(String path, String body, int expectedStatus) throws Exception {
        String response = mvc.perform(post(path).contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().is(expectedStatus))
                .andReturn().getResponse().getContentAsString();
        return json.readTree(response);
    }

    @Test
    void receptionistToDoctorFlow() throws Exception {
        UUID patientId = patient(clinic, "Rahul Kumar").getId();

        JsonNode appointment = postJson("/api/v1/appointments", """
                {"clinicId":"%s","patientId":"%s","doctorId":"%s","scheduledAt":"%sT16:00:00"}
                """.formatted(clinic.getId(), patientId, doctor.getId(), TODAY), 201);
        String appointmentId = appointment.get("id").asText();

        mvc.perform(post("/api/v1/appointments/{id}/confirm", appointmentId)).andExpect(status().isOk());
        mvc.perform(post("/api/v1/appointments/{id}/arrive", appointmentId))
                .andExpect(jsonPath("$.status").value("ARRIVED"));

        JsonNode entry = postJson("/api/v1/queue-entries", """
                {"appointmentId":"%s"}""".formatted(appointmentId), 201);
        String entryId = entry.get("id").asText();
        org.assertj.core.api.Assertions.assertThat(entry.get("tokenNumber").asInt()).isEqualTo(1);

        mvc.perform(get("/api/v1/queues/today").param("doctorId", doctor.getId().toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.queueDate").value(TODAY.toString()))
                .andExpect(jsonPath("$.waitingCount").value(1))
                .andExpect(jsonPath("$.entries[0].patientName").value("Rahul Kumar"))
                .andExpect(jsonPath("$.entries[0].patientsAhead").value(0));

        mvc.perform(post("/api/v1/queues/call-next").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"doctorId\":\"%s\"}".formatted(doctor.getId())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(entryId))
                .andExpect(jsonPath("$.status").value("CALLED"));

        mvc.perform(post("/api/v1/queue-entries/{id}/start", entryId))
                .andExpect(jsonPath("$.status").value("IN_CONSULTATION"));
        mvc.perform(post("/api/v1/queue-entries/{id}/complete", entryId))
                .andExpect(jsonPath("$.status").value("COMPLETED"));

        mvc.perform(get("/api/v1/appointments/{id}", appointmentId))
                .andExpect(jsonPath("$.status").value("COMPLETED"));
    }

    @Test
    void errorContract() throws Exception {
        mvc.perform(post("/api/v1/queues/call-next").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"doctorId\":\"%s\"}".formatted(doctor.getId())))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("QUEUE_EMPTY"));

        mvc.perform(post("/api/v1/queue-entries/{id}/start", UUID.randomUUID()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("NOT_FOUND"));

        mvc.perform(post("/api/v1/queue-entries").contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));

        Appointment booked = appointmentOn(doctor, patient(clinic, "Not Arrived"), TODAY);
        mvc.perform(post("/api/v1/queue-entries").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"appointmentId\":\"%s\"}".formatted(booked.getId())))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("INVALID_STATE"));
    }

    @Test
    void appointmentRejectsPatientFromAnotherClinic() throws Exception {
        Clinic other = clinic("Other Clinic");
        UUID foreignPatient = patient(other, "Someone Else").getId();

        mvc.perform(post("/api/v1/appointments").contentType(MediaType.APPLICATION_JSON).content("""
                        {"clinicId":"%s","patientId":"%s","doctorId":"%s","scheduledAt":"%sT16:00:00"}
                        """.formatted(clinic.getId(), foreignPatient, doctor.getId(), TODAY)))
                .andExpect(status().isNotFound());
    }

    @Test
    void inConsultationAppointmentCannotBeCancelled() throws Exception {
        Appointment appointment = arrivedAppointment(doctor, patient(clinic, "Mid Consult"));
        JsonNode entry = postJson("/api/v1/queue-entries",
                "{\"appointmentId\":\"%s\"}".formatted(appointment.getId()), 201);
        mvc.perform(post("/api/v1/queues/call-next").contentType(MediaType.APPLICATION_JSON)
                .content("{\"doctorId\":\"%s\"}".formatted(doctor.getId())));
        mvc.perform(post("/api/v1/queue-entries/{id}/start", entry.get("id").asText()));

        mvc.perform(post("/api/v1/appointments/{id}/cancel", appointment.getId()))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("QUEUE_MANAGED"));
    }
}
