package com.clinicit.clinic;

import com.clinicit.clinic.domain.Clinic;
import com.clinicit.clinic.domain.DoctorProfile;
import com.clinicit.identity.domain.Role;
import com.clinicit.queue.api.QueueEntryResponse;
import com.clinicit.queue.application.QueueService;
import com.clinicit.support.PostgresIntegrationTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Instant;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Small API additions the frontend depends on (docs/FRONTEND.md, "API gaps"). */
class ClinicAndBoardContractIntegrationTest extends PostgresIntegrationTest {

    @Autowired MockMvc mvc;
    @Autowired QueueService queue;

    Clinic clinic;
    DoctorProfile sharma;
    String deskToken;

    @BeforeEach
    void setUp() {
        clinic = clinic("City Clinic");
        sharma = doctor(clinic, "Dr. Sharma");
        deskToken = login(staff(clinic, Role.RECEPTIONIST, null, "desk@city.test"));
    }

    @Test
    void clinicEndpointGivesTheClinicLocalDate() throws Exception {
        // 20:00 UTC on 10 March is 11 March in Kolkata.
        clock.set(Instant.parse("2026-03-10T20:00:00Z"));
        String token = login(users.findByEmail("desk@city.test").orElseThrow()); // earlier token has expired
        mvc.perform(get("/api/v1/clinic").with(bearer(token)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("City Clinic"))
                .andExpect(jsonPath("$.timezone").value("Asia/Kolkata"))
                .andExpect(jsonPath("$.today").value("2026-03-11"));
        mvc.perform(get("/api/v1/clinic")).andExpect(status().isUnauthorized());
    }

    @Test
    void appointmentsCarryThePatientName() throws Exception {
        var appointment = appointmentOn(sharma, patient(clinic, "Rahul Kumar"), TODAY);

        mvc.perform(get("/api/v1/appointments").param("date", TODAY.toString()).with(bearer(deskToken)))
                .andExpect(jsonPath("$[0].patientName").value("Rahul Kumar"));
        mvc.perform(get("/api/v1/appointments/{id}", appointment.getId()).with(bearer(deskToken)))
                .andExpect(jsonPath("$.patientName").value("Rahul Kumar"));
    }

    @Test
    void boardEntriesCarryTheVersionUsedByRealtimeEvents() throws Exception {
        QueueEntryResponse entry = queue.join(frontDesk(clinic), arrivedAppointment(sharma, patient(clinic, "A")).getId());
        queue.callNext(frontDesk(clinic), sharma.getId());

        mvc.perform(get("/api/v1/queues/today").param("doctorId", sharma.getId().toString()).with(bearer(deskToken)))
                .andExpect(jsonPath("$.entries[0].version").value(1))
                .andExpect(jsonPath("$.entries[0].statusCode").value(entry.statusCode()));
    }
}
