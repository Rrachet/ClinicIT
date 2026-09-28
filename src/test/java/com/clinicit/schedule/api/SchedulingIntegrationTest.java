package com.clinicit.schedule.api;

import com.clinicit.appointment.api.AppointmentResponse;
import com.clinicit.appointment.api.CreateAppointmentRequest;
import com.clinicit.appointment.application.AppointmentService;
import com.clinicit.clinic.domain.Clinic;
import com.clinicit.clinic.domain.DoctorProfile;
import com.clinicit.common.domain.BusinessRuleException;
import com.clinicit.identity.domain.Actor;
import com.clinicit.identity.domain.Role;
import com.clinicit.patient.domain.Patient;
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
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Doctor schedules and the booking rules they enforce. The clock is Tuesday 2026-03-10,
 * 11:00 in the clinic (Asia/Kolkata).
 */
class SchedulingIntegrationTest extends PostgresIntegrationTest {

    /** Tuesdays 09:00-17:00 with lunch 13:00-14:00, 15-minute appointments; Wednesdays off. */
    private static final String WEEK = """
            {"appointmentMinutes": 15, "weeklyHours": [
              {"dayOfWeek": "MONDAY",  "start": "09:00", "end": "17:00"},
              {"dayOfWeek": "TUESDAY", "start": "09:00", "end": "17:00", "breakStart": "13:00", "breakEnd": "14:00"}
            ]}""";

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @Autowired AppointmentService appointmentService;
    @Autowired QueueService queue;

    Clinic clinic;
    DoctorProfile sharma;
    Actor desk;
    String admin;
    String reception;

    @BeforeEach
    void setUp() {
        clinic = clinic("City Clinic");
        sharma = doctor(clinic, "Dr. Sharma");
        desk = frontDesk(clinic);
        admin = login(staff(clinic, Role.ADMIN, null, "admin@city.test"));
        reception = login(staff(clinic, Role.RECEPTIONIST, null, "desk@city.test"));
    }

    private void setWeek(String body) throws Exception {
        mvc.perform(put("/api/v1/doctors/{id}/schedule", sharma.getId()).with(bearer(admin))
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk());
    }

    private ResultActions book(Patient patient, String time) throws Exception {
        return mvc.perform(post("/api/v1/appointments").with(bearer(reception))
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"patientId": "%s", "doctorId": "%s", "scheduledAt": "%s"}
                        """.formatted(patient.getId(), sharma.getId(), time)));
    }

    private AppointmentResponse bookDirect(String name, LocalDateTime at) {
        return appointmentService.create(desk, new CreateAppointmentRequest(patient(clinic, name).getId(),
                sharma.getId(), at, null));
    }

    private void refused(ResultActions result, String code) throws Exception {
        result.andExpect(status().isConflict()).andExpect(jsonPath("$.code").value(code));
    }

    @Test
    void onlyAdminsChangeSchedulesAndStaffCanReadThem() throws Exception {
        mvc.perform(put("/api/v1/doctors/{id}/schedule", sharma.getId()).with(bearer(reception))
                        .contentType(MediaType.APPLICATION_JSON).content(WEEK))
                .andExpect(status().isForbidden());
        setWeek(WEEK);

        String doctor = login(staff(clinic, Role.DOCTOR, sharma, "sharma@city.test"));
        mvc.perform(get("/api/v1/doctors/{id}/schedule", sharma.getId()).with(bearer(doctor)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.appointmentMinutes").value(15))
                .andExpect(jsonPath("$.weeklyHours.length()").value(2))
                .andExpect(jsonPath("$.weeklyHours[1].dayOfWeek").value("TUESDAY"))
                .andExpect(jsonPath("$.weeklyHours[1].breakStart").value("13:00:00"));
        mvc.perform(post("/api/v1/doctors/{id}/time-off", sharma.getId()).with(bearer(reception))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"startsAt\": \"2026-03-11T00:00\", \"endsAt\": \"2026-03-12T00:00\"}"))
                .andExpect(status().isForbidden());
    }

    @Test
    void invalidSchedulesAreRejectedWithoutChangingTheCurrentOne() throws Exception {
        setWeek(WEEK);
        for (String bad : List.of(
                "{\"appointmentMinutes\": 15, \"weeklyHours\": [{\"dayOfWeek\": \"MONDAY\", \"start\": \"17:00\", \"end\": \"09:00\"}]}",
                "{\"appointmentMinutes\": 15, \"weeklyHours\": [{\"dayOfWeek\": \"MONDAY\", \"start\": \"09:00\", \"end\": \"17:00\", \"breakStart\": \"16:30\", \"breakEnd\": \"17:30\"}]}",
                "{\"appointmentMinutes\": 15, \"weeklyHours\": [{\"dayOfWeek\": \"MONDAY\", \"start\": \"09:00\", \"end\": \"12:00\"}, {\"dayOfWeek\": \"MONDAY\", \"start\": \"13:00\", \"end\": \"17:00\"}]}",
                "{\"appointmentMinutes\": 2, \"weeklyHours\": []}")) {
            mvc.perform(put("/api/v1/doctors/{id}/schedule", sharma.getId()).with(bearer(admin))
                            .contentType(MediaType.APPLICATION_JSON).content(bad))
                    .andExpect(status().isBadRequest());
        }
        mvc.perform(get("/api/v1/doctors/{id}/schedule", sharma.getId()).with(bearer(admin)))
                .andExpect(jsonPath("$.weeklyHours.length()").value(2));
    }

    @Test
    void withoutAScheduleAnyTimeCanBeBookedAsBefore() throws Exception {
        book(patient(clinic, "A"), "2026-03-11T03:00").andExpect(status().isCreated());
        book(patient(clinic, "B"), "2026-03-11T03:00").andExpect(status().isCreated());
    }

    @Test
    void bookingsMustFitTheHoursAvoidTheBreakAndThePastAndNotOverlap() throws Exception {
        setWeek(WEEK);

        book(patient(clinic, "A"), "2026-03-10T11:15").andExpect(status().isCreated())
                .andExpect(jsonPath("$.walkIn").value(false));
        refused(book(patient(clinic, "B"), "2026-03-10T11:15"), "SLOT_TAKEN");
        refused(book(patient(clinic, "B"), "2026-03-10T11:20"), "SLOT_TAKEN");     // overlaps 11:15-11:30
        book(patient(clinic, "B"), "2026-03-10T11:30").andExpect(status().isCreated());

        refused(book(patient(clinic, "C"), "2026-03-10T10:00"), "IN_THE_PAST");
        refused(book(patient(clinic, "C"), "2026-03-10T12:50"), "ON_BREAK");
        refused(book(patient(clinic, "C"), "2026-03-10T16:50"), "OUTSIDE_HOURS");
        refused(book(patient(clinic, "C"), "2026-03-11T10:00"), "DAY_OFF");         // Wednesday
        book(patient(clinic, "C"), "2026-03-16T09:00").andExpect(status().isCreated()); // Monday, no break
    }

    @Test
    void aCancelledOrNoShowAppointmentFreesItsSlot() throws Exception {
        setWeek(WEEK);
        AppointmentResponse first = bookDirect("A", TODAY.atTime(15, 0));
        refused(book(patient(clinic, "B"), "2026-03-10T15:00"), "SLOT_TAKEN");

        appointmentService.cancel(desk, first.id());
        book(patient(clinic, "B"), "2026-03-10T15:00").andExpect(status().isCreated());
    }

    @Test
    void timeOffBlocksBookingsAndReportsTheOnesAlreadyInIt() throws Exception {
        setWeek(WEEK);
        bookDirect("Booked earlier", LocalDateTime.of(2026, 3, 16, 10, 0));

        String created = mvc.perform(post("/api/v1/doctors/{id}/time-off", sharma.getId()).with(bearer(admin))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"startsAt\": \"2026-03-16T00:00\", \"endsAt\": \"2026-03-17T00:00\", \"reason\": \"Conference\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.bookedAppointments").value(1))
                .andExpect(jsonPath("$.timeOff.reason").value("Conference"))
                .andReturn().getResponse().getContentAsString();

        refused(book(patient(clinic, "B"), "2026-03-16T11:00"), "TIME_OFF");

        String id = json.readTree(created).at("/timeOff/id").asText();
        mvc.perform(delete("/api/v1/doctors/{d}/time-off/{id}", sharma.getId(), id).with(bearer(admin)))
                .andExpect(status().isNoContent());
        book(patient(clinic, "B"), "2026-03-16T11:00").andExpect(status().isCreated());
    }

    @Test
    void walkInsAreTakenWhileTheDoctorWorksTodayAndNeverHoldASlot() throws Exception {
        setWeek(WEEK);
        clock.set(NOW.plus(Duration.ofHours(2).plusMinutes(10))); // 13:10, lunch break

        String walkIn = mvc.perform(post("/api/v1/appointments").with(bearer(reception))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"patientId\": \"%s\", \"doctorId\": \"%s\", \"walkIn\": true}"
                                .formatted(patient(clinic, "Walk In").getId(), sharma.getId())))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.walkIn").value(true))
                .andExpect(jsonPath("$.scheduledAt").value("2026-03-10T13:10:00"))
                .andReturn().getResponse().getContentAsString();
        // It can be queued like any other appointment.
        var walkInId = java.util.UUID.fromString(json.readTree(walkIn).get("id").asText());
        appointmentService.confirm(desk, walkInId);
        appointmentService.arrive(desk, walkInId);
        queue.join(desk, walkInId);

        // A booking right after the break is still free: the walk-in holds no slot.
        book(patient(clinic, "Booked"), "2026-03-10T14:00").andExpect(status().isCreated());

        // After closing, walk-ins for this doctor are refused.
        clock.set(NOW.plus(Duration.ofHours(6).plusMinutes(1))); // 17:01
        mvc.perform(post("/api/v1/appointments").with(bearer(reception))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"patientId\": \"%s\", \"doctorId\": \"%s\", \"walkIn\": true}"
                                .formatted(patient(clinic, "Late").getId(), sharma.getId())))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("NOT_WORKING_TODAY"));
    }

    @Test
    void reschedulingFollowsTheSameRulesAndIsRecordedInTheHistory() throws Exception {
        setWeek(WEEK);
        AppointmentResponse a = bookDirect("A", TODAY.atTime(15, 0));
        bookDirect("B", TODAY.atTime(15, 30));

        // Moving within its own slot is fine (it does not collide with itself).
        mvc.perform(post("/api/v1/appointments/{id}/reschedule", a.id()).with(bearer(reception))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"scheduledAt\": \"2026-03-10T15:05\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.scheduledAt").value("2026-03-10T15:05:00"));
        refused(mvc.perform(post("/api/v1/appointments/{id}/reschedule", a.id()).with(bearer(reception))
                .contentType(MediaType.APPLICATION_JSON).content("{\"scheduledAt\": \"2026-03-10T15:30\"}")), "SLOT_TAKEN");
        refused(mvc.perform(post("/api/v1/appointments/{id}/reschedule", a.id()).with(bearer(reception))
                .contentType(MediaType.APPLICATION_JSON).content("{\"scheduledAt\": \"2026-03-10T13:30\"}")), "ON_BREAK");

        assertThat(jdbc.queryForList("""
                select event_type, previous_status, scheduled_at::text as at from operational_events
                where appointment_id = ? order by seq""", a.id()))
                .extracting(row -> row.get("event_type") + " " + row.get("previous_status") + " " + row.get("at"))
                .containsExactly("BOOKED null 2026-03-10 15:00:00", "RESCHEDULED BOOKED 2026-03-10 15:05:00");

        // Once the patient has arrived, the appointment is no longer moved.
        appointmentService.confirm(desk, a.id());
        appointmentService.arrive(desk, a.id());
        refused(mvc.perform(post("/api/v1/appointments/{id}/reschedule", a.id()).with(bearer(reception))
                .contentType(MediaType.APPLICATION_JSON).content("{\"scheduledAt\": \"2026-03-10T16:00\"}")), "NOT_RESCHEDULABLE");
    }

    @Test
    void availabilityListsEverySlotWithWhyItIsTaken() throws Exception {
        setWeek(WEEK);
        bookDirect("A", TODAY.atTime(11, 15));
        jdbc.update("""
                insert into doctor_time_off (clinic_id, doctor_id, starts_at, ends_at)
                values (?, ?, '2026-03-10 16:00', '2026-03-10 17:00')""", clinic.getId(), sharma.getId());

        JsonNode day = json.readTree(mvc.perform(get("/api/v1/doctors/{id}/availability", sharma.getId())
                        .param("date", "2026-03-10").with(bearer(reception)))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString());

        assertThat(day.get("scheduled").asBoolean()).isTrue();
        assertThat(day.at("/hours/breakStart").asText()).isEqualTo("13:00:00");
        List<String> slots = new ArrayList<>();
        day.get("slots").forEach(s -> slots.add(LocalDateTime.parse(s.get("start").asText()).toLocalTime() + " "
                + (s.get("available").asBoolean() ? "free" : s.get("reason").asText())));
        // 9-13 and 14-17 in 15-minute slots.
        assertThat(slots).hasSize(28)
                .contains("10:45 IN_THE_PAST", "11:00 free", "11:15 SLOT_TAKEN", "11:30 free", "12:45 free",
                        "14:00 free", "15:45 free", "16:00 TIME_OFF", "16:45 TIME_OFF")
                .doesNotContain("13:00 free");

        JsonNode wednesday = json.readTree(mvc.perform(get("/api/v1/doctors/{id}/availability", sharma.getId())
                        .param("date", "2026-03-11").with(bearer(reception)))
                .andReturn().getResponse().getContentAsString());
        assertThat(wednesday.get("hours").isNull()).isTrue();
        assertThat(wednesday.get("slots")).isEmpty();
    }

    @Test
    void twoReceptionistsCannotBothTakeTheLastSlot() throws Exception {
        setWeek(WEEK);
        List<Patient> people = List.of(patient(clinic, "One"), patient(clinic, "Two"), patient(clinic, "Three"),
                patient(clinic, "Four"));
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(people.size());
        try {
            List<Future<Boolean>> results = new ArrayList<>();
            for (Patient p : people) {
                Callable<Boolean> attempt = () -> {
                    start.await();
                    try {
                        appointmentService.create(desk, new CreateAppointmentRequest(p.getId(), sharma.getId(),
                                TODAY.atTime(15, 0), null));
                        return true;
                    } catch (BusinessRuleException e) {
                        assertThat(e.getCode()).isEqualTo("SLOT_TAKEN");
                        return false;
                    }
                };
                results.add(pool.submit(attempt));
            }
            start.countDown();
            int booked = 0;
            for (Future<Boolean> result : results) if (result.get()) booked++;
            assertThat(booked).isEqualTo(1);
        } finally {
            pool.shutdownNow();
        }
        assertThat(jdbc.queryForObject("select count(*) from appointments where doctor_id = ?", Integer.class,
                sharma.getId())).isEqualTo(1);
    }

    @Test
    void anotherClinicCannotReadOrChangeTheSchedule() throws Exception {
        setWeek(WEEK);
        Clinic other = clinic("Other Clinic");
        String otherAdmin = login(staff(other, Role.ADMIN, null, "admin@other.test"));

        mvc.perform(get("/api/v1/doctors/{id}/schedule", sharma.getId()).with(bearer(otherAdmin)))
                .andExpect(status().isNotFound());
        mvc.perform(get("/api/v1/doctors/{id}/availability", sharma.getId()).param("date", "2026-03-10")
                        .with(bearer(otherAdmin)))
                .andExpect(status().isNotFound());
        mvc.perform(put("/api/v1/doctors/{id}/schedule", sharma.getId()).with(bearer(otherAdmin))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"appointmentMinutes\": 30, \"weeklyHours\": []}"))
                .andExpect(status().isNotFound());
        mvc.perform(post("/api/v1/doctors/{id}/time-off", sharma.getId()).with(bearer(otherAdmin))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"startsAt\": \"2026-03-11T00:00\", \"endsAt\": \"2026-03-12T00:00\"}"))
                .andExpect(status().isNotFound());
        assertThatThrownBy(() -> jdbc.update("""
                insert into doctor_working_hours (clinic_id, doctor_id, day_of_week, start_time, end_time)
                values (?, ?, 3, '09:00', '17:00')""", other.getId(), sharma.getId()))
                .hasMessageContaining("fk_working_hours_doctor_same_clinic");

        mvc.perform(get("/api/v1/doctors/{id}/schedule", sharma.getId()).with(bearer(admin)))
                .andExpect(jsonPath("$.appointmentMinutes").value(15))
                .andExpect(jsonPath("$.timeOff.length()").value(0));
    }

    @Test
    void analyticsMeasureConsultationTimeAgainstTheScheduledHours() throws Exception {
        setWeek(WEEK);
        var entry = queue.join(desk, arrivedAppointment(sharma, patient(clinic, "A")).getId());
        queue.callNext(desk, sharma.getId());
        queue.startConsultation(desk, entry.id());
        clock.set(NOW.plus(Duration.ofMinutes(42)));
        queue.complete(desk, entry.id());

        mvc.perform(get("/api/v1/analytics/doctors").param("date", "2026-03-10").with(bearer(admin)))
                .andExpect(status().isOk())
                // Tuesday 09:00-17:00 less the 13:00-14:00 break.
                .andExpect(jsonPath("$.doctors[0].scheduledMinutes").value(420))
                .andExpect(jsonPath("$.doctors[0].scheduledUtilization").value(0.1))
                // The span-based figure is unchanged: 42 minutes busy out of 42.
                .andExpect(jsonPath("$.doctors[0].utilization").value(1.0));

        DoctorProfile unscheduled = doctor(clinic, "Dr. Zed");
        mvc.perform(get("/api/v1/analytics/doctors").param("date", "2026-03-10").with(bearer(admin)))
                .andExpect(jsonPath("$.doctors[1].doctorId").value(unscheduled.getId().toString()))
                .andExpect(jsonPath("$.doctors[1].scheduledMinutes").doesNotExist());
    }

    @Test
    void waitEstimatesIncludeABreakThatFallsWithinTheWait() {
        clock.set(NOW.plus(Duration.ofMinutes(100))); // 12:40, twenty minutes before lunch
        for (String name : List.of("A", "B", "C", "D")) {
            queue.join(desk, arrivedAppointment(sharma, patient(clinic, name)).getId());
        }
        var before = waitTimePredictions.forDoctorToday(desk, sharma.getId()).entries();

        setWeekDirect();
        waitTimePredictions.reset();
        var after = waitTimePredictions.forDoctorToday(desk, sharma.getId()).entries();

        // The first patient is seen before the break; the last one waits through it.
        assertThat(before.get(0).estimatedWaitMinutes()).isLessThan(20);
        assertThat(after.get(0).estimatedWaitMinutes()).isEqualTo(before.get(0).estimatedWaitMinutes());
        assertThat(before.get(3).estimatedWaitMinutes()).isGreaterThan(20);
        assertThat(after.get(3).estimatedWaitMinutes()).isEqualTo(before.get(3).estimatedWaitMinutes() + 60);
        assertThat(after.get(3).upperBoundMinutes()).isEqualTo(before.get(3).upperBoundMinutes() + 60);
        assertThat(after.get(3).source()).isEqualTo(before.get(3).source());
    }

    private void setWeekDirect() {
        jdbc.update("""
                insert into doctor_working_hours (clinic_id, doctor_id, day_of_week, start_time, end_time, break_start, break_end)
                values (?, ?, 2, '09:00', '17:00', '13:00', '14:00')""", clinic.getId(), sharma.getId());
    }
}
