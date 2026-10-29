package com.clinicit.checkin;

import com.clinicit.clinic.domain.Clinic;
import com.clinicit.clinic.domain.DoctorProfile;
import com.clinicit.identity.domain.Role;
import com.clinicit.queue.application.QueueService;
import com.clinicit.support.PostgresIntegrationTest;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** A patient scans the clinic's QR code and is queued with a live doctor, with no receptionist. */
class SelfCheckInIntegrationTest extends PostgresIntegrationTest {

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @Autowired QueueService queue;

    Clinic clinic;
    DoctorProfile paeds;
    DoctorProfile paeds2;
    DoctorProfile general;
    String admin;
    String paedsToken;
    String desk;
    UUID child;
    UUID skin;
    UUID anyone;
    final Map<UUID, String> doctorTokens = new java.util.HashMap<>();

    @BeforeEach
    void setUp() throws Exception {
        clinic = clinic("City Clinic");
        paeds = doctor(clinic, "Dr. Iyer");
        paeds2 = doctor(clinic, "Dr. Khan");
        general = doctor(clinic, "Dr. Sharma");
        admin = login(staff(clinic, Role.ADMIN, null, "admin@city.test"));
        desk = login(staff(clinic, Role.RECEPTIONIST, null, "desk@city.test"));
        paedsToken = login(staff(clinic, Role.DOCTOR, paeds, "iyer@city.test"));
        child = createVisitType("Child (paediatrics)", false, List.of(paeds.getId(), paeds2.getId()));
        skin = createVisitType("Skin", false, List.of(general.getId()));
        anyone = createVisitType("General check-up", true, List.of());
    }

    private UUID createVisitType(String name, boolean anyDoctor, List<UUID> doctorIds) throws Exception {
        String body = json.writeValueAsString(Map.of("name", name, "anyDoctor", anyDoctor, "doctorIds", doctorIds));
        String response = mvc.perform(post("/api/v1/visit-types").with(bearer(admin))
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        return UUID.fromString(json.readTree(response).get("id").asText());
    }

    private void live(DoctorProfile doctor, String room) throws Exception {
        String token = doctor.getId().equals(paeds.getId()) ? paedsToken
                : doctorTokens.computeIfAbsent(doctor.getId(),
                        id -> login(staff(clinic, Role.DOCTOR, doctor, "live." + id + "@city.test")));
        mvc.perform(put("/api/v1/doctors/{id}/duty", doctor.getId()).with(bearer(token))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of("live", true, "room", room))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.live").value(true))
                .andExpect(jsonPath("$.room").value(room));
    }

    private ResultActions checkIn(String name, String phone, UUID visitType, String reason) throws Exception {
        var body = new java.util.HashMap<String, Object>(Map.of("fullName", name, "phone", phone, "visitTypeId", visitType));
        if (reason != null) body.put("reason", reason);
        return mvc.perform(post("/api/v1/public/check-in/{code}", clinic.getCheckInCode())
                .contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(body)));
    }

    private JsonNode body(ResultActions result) throws Exception {
        return json.readTree(result.andReturn().getResponse().getContentAsString());
    }

    @Test
    void thePageListsActiveVisitTypesAndWhichCanBeSeenNow() throws Exception {
        live(paeds, "Room 1");
        mvc.perform(put("/api/v1/visit-types/{id}", skin).with(bearer(admin)).contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(Map.of("name", "Skin", "anyDoctor", false,
                        "doctorIds", List.of(general.getId()), "active", true))))
                .andExpect(status().isOk());
        UUID retired = createVisitType("Vaccination", true, List.of());
        mvc.perform(put("/api/v1/visit-types/{id}", retired).with(bearer(admin)).contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(Map.of("name", "Vaccination", "anyDoctor", true,
                        "doctorIds", List.of(), "active", false))))
                .andExpect(status().isOk());

        mvc.perform(get("/api/v1/public/check-in/{code}", clinic.getCheckInCode()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.clinicName").value("City Clinic"))
                .andExpect(jsonPath("$.open").value(true))
                .andExpect(jsonPath("$.visitTypes.length()").value(3))
                .andExpect(jsonPath("$.visitTypes[?(@.name == 'Child (paediatrics)')].available").value(true))
                .andExpect(jsonPath("$.visitTypes[?(@.name == 'General check-up')].available").value(true))
                .andExpect(jsonPath("$.visitTypes[?(@.name == 'Skin')].available").value(false))
                // Nothing about doctors or patients.
                .andExpect(content().string(not(containsString("Iyer"))));
    }

    @Test
    void queuesThePatientWithTheLeastBusyLiveDoctorForTheirVisitType() throws Exception {
        live(paeds, "Room 1");
        live(paeds2, "Room 2");
        live(general, "Room 3");
        // Dr. Iyer already has someone waiting.
        queue.join(frontDesk(clinic), arrivedAppointment(paeds, patient(clinic, "Earlier")).getId());

        JsonNode result = body(checkIn("Asha Rao", "+91 90000-11111", child, "Fever since two days")
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.doctorName").value("Dr. Khan"))
                .andExpect(jsonPath("$.room").value("Room 2"))
                .andExpect(jsonPath("$.alreadyCheckedIn").value(false)));

        Map<String, Object> row = jdbc.queryForMap("""
                select a.walk_in, a.status, a.reason_summary, a.visit_type_id, a.doctor_id, p.phone, p.full_name
                from queue_entries q join appointments a on a.id = q.appointment_id join patients p on p.id = a.patient_id
                where q.status_code = ?""", result.get("statusCode").asText());
        assertThat(row.get("walk_in")).isEqualTo(true);
        assertThat(row.get("status")).isEqualTo("WAITING");
        assertThat(row.get("reason_summary")).isEqualTo("Child (paediatrics): Fever since two days");
        assertThat(row.get("visit_type_id")).isEqualTo(child);
        assertThat(row.get("doctor_id")).isEqualTo(paeds2.getId());
        assertThat(row.get("phone")).isEqualTo("+919000011111");
        // Booked, confirmed, arrived and queued are all in the history, with no staff actor.
        assertThat(jdbc.queryForObject(
                "select count(*) from operational_events where appointment_id = (select appointment_id from queue_entries where status_code = ?) and actor_user_id is null",
                Integer.class, result.get("statusCode").asText())).isEqualTo(4);

        // The patient's own page shows where to go once called.
        mvc.perform(get("/api/v1/public/queue-status/{code}", result.get("statusCode").asText()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("WAITING"))
                .andExpect(jsonPath("$.doctorName").value("Dr. Khan"))
                .andExpect(jsonPath("$.room").value("Room 2"));
    }

    @Test
    void anyDoctorVisitTypesGoToWhoeverIsLeastBusy() throws Exception {
        live(general, "Room 3");
        live(paeds, "Room 1");
        queue.join(frontDesk(clinic), arrivedAppointment(general, patient(clinic, "Earlier")).getId());
        checkIn("Vikram Singh", "9000022222", anyone, null)
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.doctorName").value("Dr. Iyer"));
    }

    @Test
    void scanningAgainGivesTheSamePlaceInsteadOfASecondToken() throws Exception {
        live(paeds, "Room 1");
        JsonNode first = body(checkIn("Asha Rao", "9000011111", child, null).andExpect(status().isCreated()));
        JsonNode again = body(checkIn("asha rao", "90000 11111", anyone, null)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.alreadyCheckedIn").value(true)));
        assertThat(again.get("statusCode").asText()).isEqualTo(first.get("statusCode").asText());
        assertThat(again.get("tokenNumber").asInt()).isEqualTo(first.get("tokenNumber").asInt());
        assertThat(jdbc.queryForObject("select count(*) from queue_entries", Integer.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject("select count(*) from patients", Integer.class)).isEqualTo(1);
    }

    @Test
    void refusesWhenNoLiveDoctorSeesThatVisitTypeAndCreatesNothing() throws Exception {
        live(paeds, "Room 1");
        checkIn("Asha Rao", "9000011111", skin, null)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("NO_DOCTOR_AVAILABLE"));
        assertThat(jdbc.queryForObject("select count(*) from patients", Integer.class)).isZero();
        assertThat(jdbc.queryForObject("select count(*) from appointments", Integer.class)).isZero();
    }

    @Test
    void aDoctorWhoGoesOfflineOrWasLiveYesterdayGetsNoNewPatients() throws Exception {
        live(paeds, "Room 1");
        mvc.perform(put("/api/v1/doctors/{id}/duty", paeds.getId()).with(bearer(paedsToken))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"live\": false}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.live").value(false))
                .andExpect(jsonPath("$.room").value("Room 1"));
        checkIn("Asha Rao", "9000011111", child, null).andExpect(status().isConflict());

        live(paeds2, "Room 2");
        clock.set(NOW.plus(Duration.ofDays(1)));
        mvc.perform(get("/api/v1/public/check-in/{code}", clinic.getCheckInCode()))
                .andExpect(jsonPath("$.open").value(false));
        checkIn("Asha Rao", "9000011111", child, null).andExpect(status().isConflict());
    }

    @Test
    void doctorsChangeOnlyTheirOwnDutyAndFrontDeskCanChangeAnyone() throws Exception {
        String body = "{\"live\": true, \"room\": \"Room 9\"}";
        mvc.perform(put("/api/v1/doctors/{id}/duty", general.getId()).with(bearer(paedsToken))
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isForbidden());
        mvc.perform(put("/api/v1/doctors/{id}/duty", general.getId()).with(bearer(desk))
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk());
        mvc.perform(get("/api/v1/doctors").with(bearer(desk)))
                .andExpect(jsonPath("$[?(@.displayName == 'Dr. Sharma')].live").value(true))
                .andExpect(jsonPath("$[?(@.displayName == 'Dr. Sharma')].room").value("Room 9"))
                .andExpect(jsonPath("$[?(@.displayName == 'Dr. Iyer')].live").value(false));
    }

    @Test
    void rejectsBadInputUnknownClinicsAndAnotherClinicsVisitTypes() throws Exception {
        live(paeds, "Room 1");
        checkIn("Asha Rao", "12ab", child, null).andExpect(status().isBadRequest());
        checkIn(" ", "9000011111", child, null).andExpect(status().isBadRequest());
        mvc.perform(post("/api/v1/public/check-in/{code}", "nosuchclinic00000")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of("fullName", "A", "phone", "9000011111", "visitTypeId", child))))
                .andExpect(status().isNotFound());
        mvc.perform(get("/api/v1/public/check-in/{code}", "nosuchclinic00000")).andExpect(status().isNotFound());

        Clinic other = clinic("Other Clinic");
        mvc.perform(post("/api/v1/public/check-in/{code}", other.getCheckInCode())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of("fullName", "A", "phone", "9000011111", "visitTypeId", child))))
                .andExpect(status().isNotFound());
        assertThat(jdbc.queryForObject("select count(*) from patients", Integer.class)).isZero();
    }

    @Test
    void floodsFromOneAddressAreRateLimited() throws Exception {
        String body = json.writeValueAsString(Map.of("fullName", "A", "phone", "9000011111", "visitTypeId", child));
        for (int i = 0; i < 30; i++) {
            mvc.perform(post("/api/v1/public/check-in/{code}", "nosuchclinic00000")
                    .contentType(MediaType.APPLICATION_JSON).content(body)).andExpect(status().isNotFound());
        }
        mvc.perform(post("/api/v1/public/check-in/{code}", "nosuchclinic00000")
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isTooManyRequests())
                .andExpect(jsonPath("$.code").value("RATE_LIMITED"));
    }

    @Test
    void onlyAdminsManageVisitTypesAndOnlyWithTheirOwnDoctors() throws Exception {
        String body = json.writeValueAsString(Map.of("name", "Eyes", "anyDoctor", false, "doctorIds", List.of(general.getId())));
        mvc.perform(post("/api/v1/visit-types").with(bearer(desk)).contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isForbidden());
        mvc.perform(post("/api/v1/visit-types").with(bearer(admin)).contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of("name", "skin", "anyDoctor", true, "doctorIds", List.of()))))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("VISIT_TYPE_EXISTS"));
        mvc.perform(post("/api/v1/visit-types").with(bearer(admin)).contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of("name", "Eyes", "anyDoctor", false, "doctorIds", List.of()))))
                .andExpect(status().isBadRequest());

        DoctorProfile foreign = doctor(clinic("Other Clinic"), "Dr. Elsewhere");
        mvc.perform(post("/api/v1/visit-types").with(bearer(admin)).contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of("name", "Eyes", "anyDoctor", false, "doctorIds", List.of(foreign.getId())))))
                .andExpect(status().isNotFound());

        mvc.perform(get("/api/v1/visit-types").with(bearer(desk)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(3))
                .andExpect(jsonPath("$[?(@.name == 'Child (paediatrics)')].doctorIds.length()").value(2));
    }
}
