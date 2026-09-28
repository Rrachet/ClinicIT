package com.clinicit.noshow;

import com.clinicit.appointment.api.CreateAppointmentRequest;
import com.clinicit.appointment.application.AppointmentService;
import com.clinicit.clinic.domain.Clinic;
import com.clinicit.clinic.domain.DoctorProfile;
import com.clinicit.identity.domain.Actor;
import com.clinicit.identity.domain.Role;
import com.clinicit.noshow.api.NoShowRiskResponse;
import com.clinicit.noshow.application.NoShowRiskService;
import com.clinicit.noshow.domain.NoShowRisk.Level;
import com.clinicit.patient.domain.Patient;
import com.clinicit.support.PostgresIntegrationTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.web.servlet.MockMvc;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The advisory no-show flag on a hand-built history: what it says, what it is based on, that
 * it never sees the future, and that nothing else in the product reacts to it.
 *
 * <p>Days D1-D4 are Monday 2 to Thursday 5 March; today is Tuesday 10 March, 11:00.
 * Asha misses D1, D2 and D4 and comes on D3. Bala and Chitra always come.
 */
class NoShowRiskIntegrationTest extends PostgresIntegrationTest {

    private static final ZoneId CLINIC_ZONE = ZoneId.of("Asia/Kolkata");
    private static final LocalDate D1 = LocalDate.of(2026, 3, 2);
    private static final LocalDate D2 = D1.plusDays(1);
    private static final LocalDate D3 = D1.plusDays(2);
    private static final LocalDate D4 = D1.plusDays(3);

    @Autowired NoShowRiskService risks;
    @Autowired AppointmentService appointmentService;
    @Autowired MockMvc mvc;

    Clinic clinic;
    DoctorProfile doctor;
    Actor desk;
    Patient asha;
    Patient bala;
    Patient chitra;

    @BeforeEach
    void setUp() {
        clinic = clinic("City Clinic");
        doctor = doctor(clinic, "Dr. Sharma");
        desk = frontDesk(clinic);
        asha = patient(clinic, "Asha");
        bala = patient(clinic, "Bala");
        chitra = patient(clinic, "Chitra");
    }

    private void at(LocalDate date, int hour, int minute) {
        clock.set(LocalDateTime.of(date, LocalTime.of(hour, minute)).atZone(CLINIC_ZONE).toInstant());
    }

    private UUID confirmedAt10(Patient patient, LocalDate date) {
        at(date, 8, 0);
        UUID id = appointmentService.create(desk, new CreateAppointmentRequest(patient.getId(), doctor.getId(),
                date.atTime(10, 0), null)).id();
        appointmentService.confirm(desk, id);
        return id;
    }

    private void kept(Patient patient, LocalDate date) {
        UUID id = confirmedAt10(patient, date);
        at(date, 10, 0);
        appointmentService.arrive(desk, id);
    }

    private void missed(Patient patient, LocalDate date, LocalDate markedOn) {
        UUID id = confirmedAt10(patient, date);
        at(markedOn, 12, 0);
        appointmentService.markNoShow(desk, id);
    }

    private void history() {
        missed(asha, D1, D1);
        missed(asha, D2, D2);
        kept(asha, D3);
        missed(asha, D4, D4);
        for (LocalDate day : new LocalDate[]{D1, D2, D3, D4}) {
            kept(bala, day);
            kept(chitra, day);
        }
    }

    @Test
    void flagsTodaysBookingsFromEachPatientsOwnHistory() throws Exception {
        history();
        at(TODAY, 9, 0);
        UUID ashaToday = appointmentService.create(desk, new CreateAppointmentRequest(asha.getId(), doctor.getId(),
                TODAY.atTime(16, 0), null)).id();
        UUID balaToday = appointmentService.create(desk, new CreateAppointmentRequest(bala.getId(), doctor.getId(),
                TODAY.atTime(16, 15), null)).id();
        UUID newcomer = appointmentService.create(desk, new CreateAppointmentRequest(patient(clinic, "New").getId(),
                doctor.getId(), TODAY.atTime(16, 30), null)).id();
        appointmentService.create(desk, new CreateAppointmentRequest(chitra.getId(), doctor.getId(), null, null, true));
        at(TODAY, 11, 0);

        Map<UUID, NoShowRiskResponse> flags = risks.forDay(desk, TODAY).stream()
                .collect(Collectors.toMap(NoShowRiskResponse::appointmentId, r -> r));

        // Walk-ins are here by definition: never flagged.
        assertThat(flags.keySet()).containsExactlyInAnyOrder(ashaToday, balaToday, newcomer);
        // Asha: 3 of 4 missed = 75%, clinic 3 of 12 = 25%, threshold max(30%, 50%).
        assertThat(flags.get(ashaToday)).isEqualTo(new NoShowRiskResponse(ashaToday, Level.ELEVATED, 4, 3,
                "Missed 3 of their last 4 appointments here"));
        assertThat(flags.get(balaToday).level()).isEqualTo(Level.TYPICAL);
        assertThat(flags.get(newcomer).level()).isEqualTo(Level.UNKNOWN);
        assertThat(flags.get(newcomer).reason()).isEqualTo("Not enough earlier appointments to judge");
    }

    @Test
    void theEvaluationJudgesEachDayWithOnlyWhatWasKnownThen() {
        history();
        at(TODAY, 11, 0);

        var evaluation = risks.evaluate(desk, D1, D4);

        // D1-D3: at most two earlier outcomes per patient, so nobody is judged (9 appointments).
        // D4: Asha 2 of 3 missed (67%) against a clinic rate of 2 of 9 (22%): flagged, and she missed.
        assertThat(evaluation.appointments()).isEqualTo(12);
        assertThat(evaluation.missed()).isEqualTo(3);
        assertThat(evaluation.insufficientHistory()).isEqualTo(9);
        assertThat(evaluation.flagged()).isEqualTo(1);
        assertThat(evaluation.flaggedMissed()).isEqualTo(1);
        assertThat(evaluation.missRate()).isEqualTo(0.25);
        assertThat(evaluation.flaggedMissRate()).isEqualTo(1.0);
        assertThat(evaluation.notFlaggedMissRate()).isEqualTo(0.0);
        assertThat(evaluation.recall()).isCloseTo(1 / 3.0, within(1e-9));
        assertThat(evaluation.lift()).isEqualTo(4.0);
        assertThat(evaluation.enoughData()).isFalse();

        // Judging D3 must not use D4, although D4 is in the database now.
        assertThat(risks.evaluate(desk, D3, D3).insufficientHistory()).isEqualTo(3);
    }

    @Test
    void aNoShowRecordedLateIsNotKnownBeforeItWasRecorded() {
        missed(asha, D1, D1);
        // D2's no-show is only recorded on D4 at noon, after D4 has started.
        missed(asha, D2, D4);
        kept(asha, D3);
        missed(asha, D4, D4);
        for (LocalDate day : new LocalDate[]{D1, D2, D3, D4}) {
            kept(bala, day);
        }
        at(TODAY, 11, 0);

        // As of the start of D4, Asha had two known outcomes (D1 missed, D3 kept): not judged.
        var d4 = risks.evaluate(desk, D4, D4);
        assertThat(d4.flagged()).isZero();
        assertThat(d4.insufficientHistory()).isEqualTo(1);
    }

    @Test
    void itIsAdvisoryOnlyAndScopedToTheFrontDeskAndAdmins() throws Exception {
        history();
        at(TODAY, 9, 0);
        // An elevated patient books like anyone else; the flag changes nothing.
        UUID booked = appointmentService.create(desk, new CreateAppointmentRequest(asha.getId(), doctor.getId(),
                TODAY.atTime(16, 0), null)).id();
        assertThat(risks.forDay(desk, TODAY)).singleElement().extracting(NoShowRiskResponse::level).isEqualTo(Level.ELEVATED);
        appointmentService.confirm(desk, booked);
        at(TODAY, 16, 0);
        appointmentService.arrive(desk, booked);

        String reception = login(staff(clinic, Role.RECEPTIONIST, null, "desk@city.test"));
        String admin = login(staff(clinic, Role.ADMIN, null, "admin@city.test"));
        String doctorToken = login(staff(clinic, Role.DOCTOR, doctor, "sharma@city.test"));
        mvc.perform(get("/api/v1/no-show-risk").param("date", TODAY.toString()).with(bearer(reception)))
                .andExpect(status().isOk());
        mvc.perform(get("/api/v1/no-show-risk").with(bearer(doctorToken))).andExpect(status().isForbidden());
        mvc.perform(get("/api/v1/no-show-risk/evaluation").with(bearer(reception))).andExpect(status().isForbidden());
        mvc.perform(get("/api/v1/no-show-risk/evaluation").param("from", D1.toString()).param("to", D4.toString())
                        .with(bearer(admin)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.flagged").value(1))
                .andExpect(jsonPath("$.enoughData").value(false));
        // Read-only: there is nothing to post to.
        mvc.perform(post("/api/v1/no-show-risk").with(bearer(admin))).andExpect(status().isMethodNotAllowed());

        // Another clinic sees none of it.
        Clinic other = clinic("Other Clinic");
        String otherDesk = login(staff(other, Role.RECEPTIONIST, null, "desk@other.test"));
        mvc.perform(get("/api/v1/no-show-risk").param("date", TODAY.toString()).with(bearer(otherDesk)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(0));
    }
}
