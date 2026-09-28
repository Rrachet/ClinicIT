package com.clinicit.observability;

import com.clinicit.identity.domain.Role;
import com.clinicit.queue.application.QueueService;
import com.clinicit.support.PostgresIntegrationTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.dao.DataAccessException;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Runs a realistic flow (login, register a patient, book, check in, messages, call, the
 * patient's status page, wait estimates with the ML service down) and checks that nothing
 * sensitive reached the logs: no password, bearer token, phone number, patient name, status
 * code or message text.
 */
@ExtendWith(OutputCaptureExtension.class)
class LogSafetyIntegrationTest extends PostgresIntegrationTest {

    private static final String PATIENT = "Zenobia Quintrell";
    private static final String PHONE = "+91 98765 43210";

    @Autowired MockMvc mvc;
    @Autowired QueueService queue;

    @Test
    void nothingSensitiveIsLogged(CapturedOutput output) throws Exception {
        var clinic = clinic("City Clinic");
        var doctor = doctor(clinic, "Dr. Sharma");
        var desk = staff(clinic, Role.RECEPTIONIST, null, "desk@city.test");
        String token = login(desk);
        mvc.perform(post("/api/v1/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"desk@city.test\",\"password\":\"wrong-" + PASSWORD + "\"}"))
                .andExpect(status().isUnauthorized());

        String patient = mvc.perform(post("/api/v1/patients").with(bearer(token)).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"fullName\":\"" + PATIENT + "\",\"phone\":\"" + PHONE + "\"}"))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        UUID patientId = UUID.fromString(patient.replaceAll(".*\"id\":\"([^\"]+)\".*", "$1"));
        String appointment = mvc.perform(post("/api/v1/appointments").with(bearer(token)).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"patientId\":\"%s\",\"doctorId\":\"%s\",\"scheduledAt\":\"%sT11:00:00\",\"reasonSummary\":\"Chest pain\"}"
                                .formatted(patientId, doctor.getId(), TODAY)))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        UUID appointmentId = UUID.fromString(appointment.replaceAll("^\\{\"id\":\"([^\"]+)\".*", "$1"));
        for (String step : new String[] {"confirm", "arrive"}) {
            mvc.perform(post("/api/v1/appointments/{id}/" + step, appointmentId).with(bearer(token))).andExpect(status().isOk());
        }
        String entry = mvc.perform(post("/api/v1/queue-entries").with(bearer(token)).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"appointmentId\":\"" + appointmentId + "\"}"))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        String code = entry.replaceAll(".*\"statusCode\":\"([^\"]+)\".*", "$1");
        mvc.perform(get("/api/v1/public/queue-status/{code}", code)).andExpect(status().isOk()); // ML down: logs a fallback
        mvc.perform(get("/api/v1/queues/today/wait-estimates").param("doctorId", doctor.getId().toString()).with(bearer(token)))
                .andExpect(status().isOk());
        mvc.perform(post("/api/v1/queues/call-next").with(bearer(token)).contentType(MediaType.APPLICATION_JSON)
                .content("{\"doctorId\":\"" + doctor.getId() + "\"}")).andExpect(status().isOk());
        mvc.perform(post("/api/v1/auth/logout").with(bearer(token))).andExpect(status().isNoContent());

        String bodyText = jdbc.queryForObject("select body from notifications where type = 'PATIENT_CALLED'", String.class);
        assertThat(output.getAll())
                .doesNotContain(PASSWORD)
                .doesNotContain(token)
                .doesNotContain(PHONE)
                .doesNotContain("98765")
                .doesNotContain(PATIENT)
                .doesNotContain("Chest pain")
                .doesNotContain(code)
                .doesNotContain(bodyText);
        assertThat(output.getAll()).contains("[dev notification]"); // the flow did log, just safely
    }

    @Test
    void databaseErrorsDoNotCarryRowContents() {
        var clinic = clinic("City Clinic");
        // A constraint violation's DETAIL would normally include the failing row, name and phone.
        DataAccessException error = catchThrowableOfType(DataAccessException.class, () -> jdbc.update("""
                insert into patients (id, clinic_id, full_name, phone, created_at, updated_at)
                values (gen_random_uuid(), ?, null, ?, now(), now())""", clinic.getId(), PHONE));

        assertThat(error).isNotNull();
        assertThat(String.valueOf(error.getMostSpecificCause().getMessage())).doesNotContain(PHONE).doesNotContain("98765");
    }
}
