package com.clinicit.receptionist;

import com.clinicit.clinic.domain.Clinic;
import com.clinicit.clinic.domain.DoctorProfile;
import com.clinicit.identity.domain.Role;
import com.clinicit.support.FakeClaudeServer;
import com.clinicit.support.PostgresIntegrationTest;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static com.clinicit.support.FakeClaudeServer.message;
import static com.clinicit.support.FakeClaudeServer.text;
import static com.clinicit.support.FakeClaudeServer.thinking;
import static com.clinicit.support.FakeClaudeServer.toolUse;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The AI receptionist against a fake Claude API: Claude only proposes tool calls; ClinicIT runs
 * them under its own rules, stores the transcript and replays it unchanged.
 */
class ReceptionistIntegrationTest extends PostgresIntegrationTest {

    // NOW is Tuesday 2026-03-10, 11:00 in Asia/Kolkata; tomorrow is a working day.
    private static final String TOMORROW = "2026-03-11";

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;

    FakeClaudeServer claude = FakeClaudeServer.get();
    Clinic clinic;
    DoctorProfile paeds;
    String admin;
    UUID child;

    @BeforeEach
    void setUp() throws Exception {
        clinic = clinic("City Clinic");
        paeds = doctor(clinic, "Dr. Iyer");
        admin = login(staff(clinic, Role.ADMIN, null, "admin@city.test"));
        mvc.perform(put("/api/v1/doctors/{id}/schedule", paeds.getId()).with(bearer(admin))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"appointmentMinutes": 15, "weeklyHours": [
                                  {"dayOfWeek": "TUESDAY", "start": "09:00", "end": "18:00"},
                                  {"dayOfWeek": "WEDNESDAY", "start": "09:00", "end": "18:00"}]}"""))
                .andExpect(status().isOk());
        String created = mvc.perform(post("/api/v1/visit-types").with(bearer(admin)).contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of("name", "Child (under 16)", "anyDoctor", false,
                                "doctorIds", List.of(paeds.getId())))))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        child = UUID.fromString(json.readTree(created).get("id").asText());
    }

    private ResultActions say(UUID conversationId, String text) throws Exception {
        Map<String, Object> body = new HashMap<>();
        body.put("message", text);
        if (conversationId != null) body.put("conversationId", conversationId.toString());
        return mvc.perform(post("/api/v1/public/receptionist/{code}/messages", clinic.getCheckInCode())
                .contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(body)));
    }

    private JsonNode body(ResultActions result) throws Exception {
        return json.readTree(result.andReturn().getResponse().getContentAsString());
    }

    private JsonNode request(int index) throws Exception {
        return json.readTree(claude.requests().get(index).body());
    }

    @Test
    void booksALaterSlotThroughTheToolsAndUnderTheClinicsRules() throws Exception {
        claude.reply(message("tool_use", "[%s, %s]".formatted(thinking("sig-1"),
                toolUse("t1", "find_slots", "{\"visit_type_id\":\"%s\",\"date\":\"%s\"}".formatted(child, TOMORROW)))));
        claude.reply(message("tool_use", "[%s]".formatted(toolUse("t2", "book_appointment", """
                {"visit_type_id":"%s","doctor_id":"%s","date":"%s","time":"10:00",
                 "full_name":"Aarav Kumar","phone":"98765 43210","note":"fever since last night"}"""
                .formatted(child, paeds.getId(), TOMORROW)))));
        claude.reply(message("end_turn", "[%s]".formatted(text("Done! Aarav is booked with Dr. Iyer tomorrow at 10:00."))));

        JsonNode reply = body(say(null, "Book my son tomorrow at 10, yes that's all correct")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.reply").value("Done! Aarav is booked with Dr. Iyer tomorrow at 10:00."))
                .andExpect(jsonPath("$.actions[0].type").value("BOOKED"))
                .andExpect(jsonPath("$.actions[0].doctorName").value("Dr. Iyer"))
                .andExpect(jsonPath("$.actions[0].startsAt").value("2026-03-11T10:00:00")));
        assertThat(reply.get("conversationId").asText()).isNotBlank();

        Map<String, Object> row = jdbc.queryForMap("""
                select a.status, a.walk_in, a.scheduled_at, a.visit_type_id, a.reason_summary, p.full_name, p.phone
                from appointments a join patients p on p.id = a.patient_id""");
        assertThat(row.get("status")).isEqualTo("CONFIRMED");
        assertThat(row.get("walk_in")).isEqualTo(false);
        assertThat(row.get("scheduled_at").toString()).startsWith("2026-03-11 10:00");
        assertThat(row.get("visit_type_id")).isEqualTo(child);
        assertThat(row.get("reason_summary")).isEqualTo("Child (under 16): fever since last night");
        assertThat(row.get("phone")).isEqualTo("9876543210");

        // The first request: model, effort, tools (strict, in a fixed order), caching, fallbacks.
        JsonNode first = request(0);
        assertThat(first.get("model").asText()).isEqualTo("claude-opus-5-5");
        assertThat(first.at("/output_config/effort").asText()).isEqualTo("low");
        assertThat(first.get("fallbacks").asText()).isEqualTo("default");
        assertThat(claude.requests().get(0).betaHeader()).contains("server-side-fallback-2026-07-01");
        assertThat(first.get("cache_control").get("type").asText()).isEqualTo("ephemeral");
        assertThat(first.get("system").get(1).get("text").asText()).contains("City Clinic").contains("2026-03-10");
        assertThat(first.get("tools").findValuesAsText("name"))
                .containsExactly("list_visit_types", "find_slots", "book_appointment", "check_in_now");
        assertThat(first.get("tools").get(1).get("strict").asBoolean()).isTrue();

        // The slot list went back to the model; the thinking block was replayed unchanged.
        JsonNode second = request(1);
        JsonNode toolResult = second.get("messages").get(2).get("content").get(0);
        assertThat(toolResult.get("tool_use_id").asText()).isEqualTo("t1");
        assertThat(toolResult.get("content").asText()).contains("\"time\":\"10:00\"").contains("Dr. Iyer");
        assertThat(second.get("messages").get(1).get("content").get(0).get("signature").asText()).isEqualTo("sig-1");
    }

    @Test
    void checksInAPatientWhoIsHereAndHandsTheirQueuePageToTheChat() throws Exception {
        mvc.perform(put("/api/v1/doctors/{id}/duty", paeds.getId()).with(bearer(admin))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"live\": true, \"room\": \"Room 2\"}"))
                .andExpect(status().isOk());
        claude.reply(message("tool_use", "[%s]".formatted(toolUse("t1", "check_in_now", """
                {"visit_type_id":"%s","full_name":"Meera Nair","phone":"9000000031","note":""}""".formatted(child)))));
        claude.reply(message("end_turn", "[%s]".formatted(text("You're checked in: token #1, Dr. Iyer in Room 2."))));

        JsonNode reply = body(say(null, "I'm here with my daughter").andExpect(status().isOk())
                .andExpect(jsonPath("$.actions[0].type").value("QUEUED"))
                .andExpect(jsonPath("$.actions[0].tokenNumber").value(1))
                .andExpect(jsonPath("$.actions[0].room").value("Room 2")));
        String statusCode = reply.at("/actions/0/statusCode").asText();
        assertThat(jdbc.queryForObject("select status from queue_entries where status_code = ?", String.class, statusCode))
                .isEqualTo("WAITING");
        // The model is told the token and room, never the patient's private status code.
        assertThat(request(1).toString()).contains("\"token\\\":1").doesNotContain(statusCode);
    }

    @Test
    void theNextTurnReplaysTheStoredTranscript() throws Exception {
        claude.reply(message("end_turn", "[%s, %s]".formatted(thinking("sig-a"), text("Are you at the clinic now?"))));
        UUID conversation = UUID.fromString(body(say(null, "hi").andExpect(status().isOk())).get("conversationId").asText());
        claude.reply(message("end_turn", "[%s]".formatted(text("Which visit type?"))));
        say(conversation, "yes").andExpect(status().isOk()).andExpect(jsonPath("$.conversationId").value(conversation.toString()));

        JsonNode messages = request(1).get("messages");
        assertThat(messages).hasSize(3);
        assertThat(messages.get(0).toString()).contains("hi");
        assertThat(messages.get(1).get("content").get(0).get("signature").asText()).isEqualTo("sig-a");
        assertThat(messages.get(2).toString()).contains("yes");
    }

    @Test
    void aDeclinedOrFailedTurnIsForgottenSoThePatientCanTryAgain() throws Exception {
        claude.reply(message("end_turn", "[%s]".formatted(text("Hello!"))));
        UUID conversation = UUID.fromString(body(say(null, "hi")).get("conversationId").asText());

        claude.reply(message("refusal", "[]"));
        say(conversation, "something odd").andExpect(status().isOk())
                .andExpect(jsonPath("$.reply").value(org.hamcrest.Matchers.startsWith("Sorry, I couldn't do that")));

        claude.reply(message("end_turn", "[%s]".formatted(text("How can I help?"))));
        say(conversation, "book please").andExpect(status().isOk());
        String replayed = request(2).get("messages").toString();
        assertThat(replayed).doesNotContain("something odd").contains("book please");
    }

    @Test
    void toolErrorsGoBackToTheModelAndBookNothing() throws Exception {
        claude.reply(message("tool_use", "[%s]".formatted(toolUse("t1", "book_appointment", """
                {"visit_type_id":"%s","doctor_id":"%s","date":"2026-03-11","time":"13:07",
                 "full_name":"Aarav Kumar","phone":"98765 43210","note":""}""".formatted(child, paeds.getId())))));
        claude.reply(message("end_turn", "[%s]".formatted(text("That time isn't free; shall I look for another?"))));
        say(null, "book 13:07 tomorrow").andExpect(status().isOk()).andExpect(jsonPath("$.actions").isEmpty());

        JsonNode result = request(1).get("messages").get(2).get("content").get(0);
        assertThat(result.get("is_error").asBoolean()).isTrue();
        assertThat(jdbc.queryForObject("select count(*) from appointments", Integer.class)).isZero();
    }

    @Test
    void aBookedPatientWhoScansInOnArrivalIsQueuedForTheirBooking() throws Exception {
        claude.reply(message("tool_use", "[%s]".formatted(toolUse("t1", "book_appointment", """
                {"visit_type_id":"%s","doctor_id":"%s","date":"2026-03-10","time":"16:00",
                 "full_name":"Aarav Kumar","phone":"98765 43210","note":""}""".formatted(child, paeds.getId())))));
        claude.reply(message("end_turn", "[%s]".formatted(text("Booked for 16:00 today."))));
        say(null, "today 4pm").andExpect(status().isOk()).andExpect(jsonPath("$.actions[0].type").value("BOOKED"));
        UUID booked = jdbc.queryForObject("select id from appointments", UUID.class);

        // The doctor is not live: a booked patient is still queued for their own appointment.
        mvc.perform(post("/api/v1/public/check-in/{code}", clinic.getCheckInCode()).contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of("fullName", "aarav kumar", "phone", "9876543210",
                                "visitTypeId", child))))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.doctorName").value("Dr. Iyer"));
        assertThat(jdbc.queryForObject("select appointment_id from queue_entries", UUID.class)).isEqualTo(booked);
        assertThat(jdbc.queryForObject("select count(*) from appointments", Integer.class)).isEqualTo(1);
    }

    @Test
    void anUnreachableClaudeApiAnswers503AndStoresNothing() throws Exception {
        claude.reply(400, "{\"type\":\"error\",\"error\":{\"type\":\"invalid_request_error\",\"message\":\"bad\"}}");
        say(null, "hello").andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.code").value("RECEPTIONIST_UNAVAILABLE"));
        assertThat(jdbc.queryForObject("select count(*) from receptionist_conversations", Integer.class)).isZero();
    }

    @Test
    void theInfoEndpointNeedsNoLoginAndUnknownClinicsAre404() throws Exception {
        mvc.perform(get("/api/v1/public/receptionist/{code}", clinic.getCheckInCode()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.clinicName").value("City Clinic"))
                .andExpect(jsonPath("$.enabled").value(true))
                .andExpect(jsonPath("$.greeting").isNotEmpty());
        mvc.perform(get("/api/v1/public/receptionist/{code}", "nosuchclinic0000")).andExpect(status().isNotFound());
        say(null, "x".repeat(501)).andExpect(status().isBadRequest());
        assertThat(claude.requests()).isEmpty();
    }
}
