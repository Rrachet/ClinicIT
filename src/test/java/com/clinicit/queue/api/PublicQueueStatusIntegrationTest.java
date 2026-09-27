package com.clinicit.queue.api;

import com.clinicit.clinic.domain.Clinic;
import com.clinicit.clinic.domain.DoctorProfile;
import com.clinicit.identity.domain.Actor;
import com.clinicit.patient.domain.Patient;
import com.clinicit.queue.application.QueueService;
import com.clinicit.support.PostgresIntegrationTest;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Duration;
import java.util.HashSet;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** The anonymous patient status page: by unguessable code, read-only, no patient data. */
class PublicQueueStatusIntegrationTest extends PostgresIntegrationTest {

    private static final Set<String> FIELDS = Set.of(
            "clinicName", "doctorName", "queueDate", "tokenNumber", "status", "currentToken", "patientsAhead",
            // Phase 8: an approximate range only (no model details, nothing about the patient).
            "estimatedWait");

    @Autowired MockMvc mvc;
    @Autowired QueueService queue;
    @Autowired ObjectMapper json;

    Clinic clinic;
    DoctorProfile sharma;
    Actor desk;

    @BeforeEach
    void setUp() {
        clinic = clinic("City Clinic");
        sharma = doctor(clinic, "Dr. Sharma");
        desk = frontDesk(clinic);
    }

    private QueueEntryResponse join(Patient patient) {
        return queue.join(desk, arrivedAppointment(sharma, patient).getId());
    }

    private JsonNode publicStatus(String code) throws Exception {
        return json.readTree(mvc.perform(get("/api/v1/public/queue-status/{code}", code))
                .andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", "no-store"))
                .andReturn().getResponse().getContentAsString());
    }

    @Test
    void showsPositionWithoutLoginAndWithoutPatientData() throws Exception {
        QueueEntryResponse first = join(patient(clinic, "First Patient"));
        Patient me = patient(clinic, "Private Person");
        QueueEntryResponse mine = join(me);
        queue.callNext(desk, sharma.getId());

        JsonNode body = publicStatus(mine.statusCode());

        assertThat(body.get("tokenNumber").asInt()).isEqualTo(mine.tokenNumber());
        assertThat(body.get("currentToken").asInt()).isEqualTo(first.tokenNumber());
        assertThat(body.get("patientsAhead").asLong()).isZero();
        assertThat(body.get("status").asText()).isEqualTo("WAITING");
        assertThat(body.get("doctorName").asText()).isEqualTo("Dr. Sharma");
        assertThat(body.get("clinicName").asText()).isEqualTo("City Clinic");

        Set<String> fields = new HashSet<>();
        body.fieldNames().forEachRemaining(fields::add);
        assertThat(fields).isEqualTo(FIELDS);
        Set<String> estimateFields = new HashSet<>();
        body.get("estimatedWait").fieldNames().forEachRemaining(estimateFields::add);
        assertThat(estimateFields).containsExactlyInAnyOrder("estimatedWaitMinutes", "lowerBoundMinutes", "upperBoundMinutes");
        assertThat(body.toString())
                .doesNotContain("Private Person")
                .doesNotContain(me.getPhone())
                .doesNotContain(mine.id().toString())
                .doesNotContain(mine.appointmentId().toString());
    }

    @Test
    void countsOnlyWaitingPatientsAheadOfYou() throws Exception {
        join(patient(clinic, "A"));
        QueueEntryResponse b = join(patient(clinic, "B"));
        QueueEntryResponse c = join(patient(clinic, "C"));
        queue.skip(desk, b.id());

        assertThat(publicStatus(c.statusCode()).get("patientsAhead").asLong()).isEqualTo(1);
        assertThat(publicStatus(c.statusCode()).get("currentToken").isNull()).isTrue();
    }

    @Test
    void followsTheEntryThroughItsLifecycle() throws Exception {
        QueueEntryResponse mine = join(patient(clinic, "Me"));
        queue.callNext(desk, sharma.getId());
        assertThat(publicStatus(mine.statusCode()).get("status").asText()).isEqualTo("CALLED");
        assertThat(publicStatus(mine.statusCode()).get("currentToken").asInt()).isEqualTo(mine.tokenNumber());

        queue.startConsultation(desk, mine.id());
        queue.complete(desk, mine.id());
        assertThat(publicStatus(mine.statusCode()).get("status").asText()).isEqualTo("COMPLETED");
    }

    @Test
    void unknownMalformedAndPastDayCodesAreNotFound() throws Exception {
        QueueEntryResponse mine = join(patient(clinic, "Me"));

        mvc.perform(get("/api/v1/public/queue-status/{code}", "AAAAAAAAAAAAAAAAAAAAAA"))
                .andExpect(status().isNotFound());
        mvc.perform(get("/api/v1/public/queue-status/{code}", "short")).andExpect(status().isNotFound());
        mvc.perform(get("/api/v1/public/queue-status/{code}", "' or 1=1 --xxxxxxxxxxxx"))
                .andExpect(status().isNotFound());

        clock.set(NOW.plus(Duration.ofDays(1)));
        mvc.perform(get("/api/v1/public/queue-status/{code}", mine.statusCode()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("NOT_FOUND"));
    }

    @Test
    void codesAreUnguessableAndUniquePerEntry() {
        QueueEntryResponse a = join(patient(clinic, "A"));
        QueueEntryResponse b = join(patient(clinic, "B"));

        assertThat(a.statusCode()).hasSize(22).matches("[A-Za-z0-9_-]+").isNotEqualTo(b.statusCode());
    }

    @Test
    void theCodeGrantsNothingElse() throws Exception {
        QueueEntryResponse mine = join(patient(clinic, "Me"));

        mvc.perform(get("/api/v1/queue-entries/{id}", mine.id()).header("Authorization", "Bearer " + mine.statusCode()))
                .andExpect(status().isUnauthorized());
        mvc.perform(post("/api/v1/queue-entries/{id}/skip", mine.id())).andExpect(status().isUnauthorized());
    }
}
