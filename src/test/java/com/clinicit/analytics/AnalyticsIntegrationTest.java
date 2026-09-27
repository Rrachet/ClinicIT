package com.clinicit.analytics;

import com.clinicit.analytics.api.DailySummaryResponse;
import com.clinicit.analytics.api.DoctorAnalyticsResponse;
import com.clinicit.analytics.api.NoShowResponse;
import com.clinicit.analytics.api.QueueAnalyticsResponse;
import com.clinicit.analytics.api.WaitTimesResponse;
import com.clinicit.analytics.application.AnalyticsService;
import com.clinicit.appointment.api.CreateAppointmentRequest;
import com.clinicit.appointment.application.AppointmentService;
import com.clinicit.clinic.domain.Clinic;
import com.clinicit.clinic.domain.DoctorProfile;
import com.clinicit.common.domain.ForbiddenException;
import com.clinicit.common.domain.NotFoundException;
import com.clinicit.identity.domain.Actor;
import com.clinicit.identity.domain.Role;
import com.clinicit.queue.api.QueueEntryResponse;
import com.clinicit.queue.application.QueueService;
import com.clinicit.support.PostgresIntegrationTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.web.servlet.MockMvc;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.within;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Analytics against a scripted clinic day whose every figure was worked out by hand.
 * All times are clinic-local (Asia/Kolkata); the test JVM runs in America/Los_Angeles.
 */
class AnalyticsIntegrationTest extends PostgresIntegrationTest {

    private static final ZoneId CLINIC_ZONE = ZoneId.of("Asia/Kolkata");

    @Autowired AnalyticsService analytics;
    @Autowired AppointmentService appointmentService;
    @Autowired QueueService queue;
    @Autowired MockMvc mvc;

    Clinic clinic;
    DoctorProfile drA;
    DoctorProfile drB;
    Actor desk;

    @BeforeEach
    void setUp() {
        clinic = clinic("City Clinic");
        drA = doctor(clinic, "Dr. A");
        drB = doctor(clinic, "Dr. B");
        desk = frontDesk(clinic);
    }

    private void at(LocalDate date, int hour, int minute) {
        clock.set(LocalDateTime.of(date, LocalTime.of(hour, minute)).atZone(CLINIC_ZONE).toInstant());
    }

    private void at(int hour, int minute) {
        at(TODAY, hour, minute);
    }

    private UUID book(DoctorProfile doctor, LocalDate date, int hour, int minute) {
        return appointmentService.create(desk, new CreateAppointmentRequest(
                patient(clinic, "Patient").getId(), doctor.getId(),
                LocalDateTime.of(date, LocalTime.of(hour, minute)), null)).id();
    }

    private UUID book(DoctorProfile doctor, int hour, int minute) {
        return book(doctor, TODAY, hour, minute);
    }

    /** Books, confirms, marks arrived and joins the queue, all at the current clock time. */
    private QueueEntryResponse checkIn(DoctorProfile doctor, LocalDate date, int hour, int minute) {
        UUID id = book(doctor, date, hour, minute);
        appointmentService.confirm(desk, id);
        appointmentService.arrive(desk, id);
        return queue.join(desk, id);
    }

    private QueueEntryResponse checkIn(DoctorProfile doctor, int hour, int minute) {
        return checkIn(doctor, TODAY, hour, minute);
    }

    /**
     * Dr. A: P1, P2, P3 seen; P3 was skipped and came back; P7 never came.
     * Dr. B: P4 seen, P5 still waiting, P6 cancelled, P8 booked for later.
     */
    private void scriptedDay() {
        at(9, 0);
        QueueEntryResponse p1 = checkIn(drA, 9, 0);
        UUID p7 = book(drA, 9, 0);
        appointmentService.confirm(desk, p7);
        book(drB, 12, 0);
        UUID p6 = book(drB, 16, 0);
        at(9, 10);
        QueueEntryResponse p2 = checkIn(drA, 9, 0);
        at(9, 20);
        QueueEntryResponse p3 = checkIn(drA, 9, 30);
        at(9, 30);
        queue.callNext(desk, drA.getId());
        queue.startConsultation(desk, p1.id());
        at(9, 45);
        queue.complete(desk, p1.id());
        at(9, 50);
        queue.callNext(desk, drA.getId());
        at(9, 52);
        queue.startConsultation(desk, p2.id());
        at(10, 0);
        QueueEntryResponse p4 = checkIn(drB, 10, 0);
        appointmentService.markNoShow(desk, p7);
        appointmentService.cancel(desk, p6);
        at(10, 12);
        queue.complete(desk, p2.id());
        at(10, 15);
        queue.callNext(desk, drA.getId());
        at(10, 16);
        queue.skip(desk, p3.id());
        at(10, 20);
        queue.requeue(desk, p3.id());
        at(10, 25);
        queue.callNext(desk, drA.getId());
        queue.startConsultation(desk, p3.id());
        at(10, 30);
        queue.callNext(desk, drB.getId());
        queue.startConsultation(desk, p4.id());
        at(10, 35);
        queue.complete(desk, p3.id());
        at(10, 40);
        checkIn(drB, 10, 30);
        at(10, 50);
        queue.complete(desk, p4.id());
        at(11, 0);
    }

    @Test
    void summaryOfTheScriptedDay() {
        scriptedDay();

        DailySummaryResponse today = analytics.summary(desk, null, null);

        assertThat(today.date()).isEqualTo(TODAY);
        assertThat(today.timezone()).isEqualTo("Asia/Kolkata");
        assertThat(today.scheduledAppointments()).isEqualTo(8);
        assertThat(today.patients()).isEqualTo(5);
        assertThat(today.completedConsultations()).isEqualTo(4);
        // Waits (first call − join): 30, 40, 55 (P3's first call, before the skip), 30 minutes.
        assertThat(today.averageWaitSeconds()).isEqualTo(2325.0);
        assertThat(today.medianWaitSeconds()).isEqualTo(2100.0);
        // Consultations: 15, 20, 10, 20 minutes.
        assertThat(today.averageConsultationSeconds()).isEqualTo(975.0);
        // First call − scheduled time: 30, 50, 45, 30 minutes.
        assertThat(today.averageDelaySeconds()).isEqualTo(2325.0);
        assertThat(today.cancellations()).isEqualTo(1);
        assertThat(today.noShows()).isEqualTo(1);
        assertThat(today.cancellationRate()).isEqualTo(1.0 / 8);
        assertThat(today.noShowRate()).isEqualTo(1.0 / 7);
        assertThat(today.currentQueueLength()).isEqualTo(1);
    }

    @Test
    void waitTimesIncludingTheMedianAndTheHourlyTrend() {
        scriptedDay();

        WaitTimesResponse waits = analytics.waitTimes(desk, TODAY, null);

        assertThat(waits.calledPatients()).isEqualTo(4);
        assertThat(waits.averageWaitSeconds()).isEqualTo(2325.0);
        assertThat(waits.medianWaitSeconds()).isEqualTo(2100.0);
        // Sorted 1800, 1800, 2400, 3300: the 90th percentile interpolates 70% from 2400 to 3300.
        assertThat(waits.p90WaitSeconds()).isCloseTo(3030.0, within(1e-6));
        assertThat(waits.maxWaitSeconds()).isEqualTo(3300.0);
        assertThat(waits.byHour()).containsExactly(
                new WaitTimesResponse.Hour(9, 2, 2100.0, 2100.0),
                new WaitTimesResponse.Hour(10, 2, 2550.0, 2550.0));
    }

    @Test
    void medianOfAnOddNumberOfWaitsIsTheMiddleOne() {
        at(9, 0);
        QueueEntryResponse first = checkIn(drA, 9, 0);
        QueueEntryResponse second = checkIn(drA, 9, 0);
        QueueEntryResponse third = checkIn(drA, 9, 0);
        at(9, 10);
        queue.callNext(desk, drA.getId());
        queue.skip(desk, first.id());
        at(9, 20);
        queue.callNext(desk, drA.getId());
        queue.skip(desk, second.id());
        at(10, 30);
        queue.callNext(desk, drA.getId());
        queue.skip(desk, third.id());
        at(11, 0);

        // 10, 20 and 90 minutes: the median is 20 minutes, the mean is pulled up to 40.
        WaitTimesResponse waits = analytics.waitTimes(desk, TODAY, null);
        assertThat(waits.medianWaitSeconds()).isEqualTo(1200.0);
        assertThat(waits.averageWaitSeconds()).isEqualTo(2400.0);
    }

    @Test
    void perDoctorFigures() {
        scriptedDay();

        DoctorAnalyticsResponse doctors = analytics.doctors(desk, TODAY);

        assertThat(doctors.doctors()).hasSize(2);
        DoctorAnalyticsResponse.Doctor a = doctors.doctors().get(0);
        assertThat(a.doctorName()).isEqualTo("Dr. A");
        assertThat(a.patientsCalled()).isEqualTo(3);
        assertThat(a.patientsHandled()).isEqualTo(3);
        assertThat(a.averageWaitSeconds()).isEqualTo(2500.0);
        assertThat(a.averageConsultationSeconds()).isEqualTo(900.0);
        assertThat(a.consultationSeconds()).isEqualTo(2700.0);
        // 45 minutes consulting over the 65 minutes from first call (09:30) to last completion (10:35).
        assertThat(a.utilization()).isCloseTo(2700.0 / 3900, within(1e-9));

        DoctorAnalyticsResponse.Doctor b = doctors.doctors().get(1);
        assertThat(b.doctorName()).isEqualTo("Dr. B");
        assertThat(b.patientsCalled()).isEqualTo(1);
        assertThat(b.patientsHandled()).isEqualTo(1);
        assertThat(b.averageWaitSeconds()).isEqualTo(1800.0);
        assertThat(b.utilization()).isEqualTo(1.0);
    }

    @Test
    void queueLengthAndThroughputByHour() {
        scriptedDay();

        QueueAnalyticsResponse q = analytics.queue(desk, TODAY, null);

        assertThat(q.currentQueueLength()).isEqualTo(1);
        // Hours 0..11: it is 11:00 at the clinic.
        assertThat(q.byHour()).hasSize(12);
        assertThat(q.byHour().subList(0, 9)).allSatisfy(hour -> {
            assertThat(hour.joined()).isZero();
            assertThat(hour.queueLength()).isZero();
        });
        assertThat(q.byHour().subList(9, 12)).containsExactly(
                new QueueAnalyticsResponse.Hour(9, 3, 1, 1),
                new QueueAnalyticsResponse.Hour(10, 2, 3, 1),
                new QueueAnalyticsResponse.Hour(11, 0, 0, 1));
    }

    @Test
    void skippedRequeuedAndNoShowPatientsAreCountedOnce() {
        at(9, 0);
        QueueEntryResponse left = checkIn(drA, 9, 0);
        QueueEntryResponse back = checkIn(drA, 9, 0);
        UUID arrivedThenLeft = book(drA, 9, 0);
        appointmentService.confirm(desk, arrivedThenLeft);
        appointmentService.arrive(desk, arrivedThenLeft);
        at(9, 10);
        queue.callNext(desk, drA.getId());
        queue.skip(desk, left.id());
        queue.skip(desk, back.id());
        at(9, 20);
        queue.markNoShow(desk, left.id());
        appointmentService.markNoShow(desk, arrivedThenLeft);
        queue.requeue(desk, back.id());
        at(9, 40);
        queue.callNext(desk, drA.getId());
        queue.startConsultation(desk, back.id());
        at(9, 50);
        queue.complete(desk, back.id());
        at(11, 0);

        DailySummaryResponse today = analytics.summary(desk, TODAY, null);
        assertThat(today.patients()).isEqualTo(2);
        assertThat(today.completedConsultations()).isEqualTo(1);
        assertThat(today.noShows()).isEqualTo(2);
        assertThat(today.noShowRate()).isEqualTo(2.0 / 3);
        // Called at 09:10 ("left") and 09:40 ("back", skipped while waiting, so first called at 09:40).
        assertThat(today.medianWaitSeconds()).isEqualTo((600.0 + 2400.0) / 2);
        assertThat(today.currentQueueLength()).isZero();

        NoShowResponse noShows = analytics.noShows(desk, TODAY, TODAY, null);
        assertThat(noShows.leftQueue()).isEqualTo(1);
        assertThat(noShows.leftBeforeQueue()).isEqualTo(1);
        assertThat(noShows.neverArrived()).isZero();
    }

    @Test
    void anEmptyDayHasZeroCountsAndNoAveragesOrRates() {
        DailySummaryResponse today = analytics.summary(desk, null, null);
        assertThat(today.patients()).isZero();
        assertThat(today.completedConsultations()).isZero();
        assertThat(today.averageWaitSeconds()).isNull();
        assertThat(today.medianWaitSeconds()).isNull();
        assertThat(today.averageConsultationSeconds()).isNull();
        assertThat(today.averageDelaySeconds()).isNull();
        assertThat(today.noShowRate()).isNull();
        assertThat(today.cancellationRate()).isNull();
        assertThat(today.currentQueueLength()).isZero();

        WaitTimesResponse waits = analytics.waitTimes(desk, null, null);
        assertThat(waits.calledPatients()).isZero();
        assertThat(waits.p90WaitSeconds()).isNull();
        assertThat(waits.byHour()).isEmpty();

        assertThat(analytics.doctors(desk, null).doctors()).extracting(
                        DoctorAnalyticsResponse.Doctor::patientsHandled, DoctorAnalyticsResponse.Doctor::utilization)
                .containsOnly(org.assertj.core.groups.Tuple.tuple(0L, null));

        QueueAnalyticsResponse q = analytics.queue(desk, null, null);
        assertThat(q.byHour()).hasSize(12).allSatisfy(hour -> assertThat(hour.queueLength()).isZero());
        assertThat(analytics.queue(desk, TODAY.minusDays(1), null).byHour()).hasSize(24);
        assertThat(analytics.queue(desk, TODAY.plusDays(1), null).byHour()).isEmpty();

        NoShowResponse noShows = analytics.noShows(desk, null, null, null);
        assertThat(noShows.from()).isEqualTo(TODAY.minusDays(29));
        assertThat(noShows.to()).isEqualTo(TODAY);
        assertThat(noShows.noShowRate()).isNull();
        assertThat(noShows.byDay()).isEmpty();
    }

    @Test
    void daysAreClinicLocalNotUtcOrServerLocal() {
        LocalDate yesterday = TODAY.minusDays(1);
        // 23:50 on the 9th and 00:10 on the 10th in India are both on the 9th in UTC (18:20Z, 18:40Z)
        // and in Los Angeles, where the test JVM runs.
        at(yesterday, 23, 50);
        checkIn(drA, yesterday, 23, 45);
        at(TODAY, 0, 10);
        checkIn(drA, TODAY, 0, 5);
        at(11, 0);

        assertThat(analytics.summary(desk, yesterday, null).patients()).isEqualTo(1);
        assertThat(analytics.summary(desk, TODAY, null).patients()).isEqualTo(1);
        assertThat(analytics.summary(desk, TODAY, null).scheduledAppointments()).isEqualTo(1);
        assertThat(analytics.queue(desk, TODAY, null).byHour().get(0).joined()).isEqualTo(1);
        assertThat(analytics.queue(desk, yesterday, null).byHour().get(23).joined()).isEqualTo(1);
        // Yesterday's patient is still "waiting" but is not in today's queue.
        assertThat(analytics.queue(desk, TODAY, null).currentQueueLength()).isEqualTo(1);
    }

    @Test
    void noShowsOverARange() {
        LocalDate twoDaysAgo = TODAY.minusDays(2);
        at(twoDaysAgo, 9, 0);
        UUID missed = book(drA, twoDaysAgo, 9, 0);
        appointmentService.confirm(desk, missed);
        book(drA, twoDaysAgo, 10, 0);
        at(twoDaysAgo, 12, 0);
        appointmentService.markNoShow(desk, missed);
        at(11, 0);
        appointmentService.cancel(desk, book(drB, 15, 0));

        NoShowResponse range = analytics.noShows(desk, twoDaysAgo, TODAY, null);
        assertThat(range.scheduledAppointments()).isEqualTo(3);
        assertThat(range.noShows()).isEqualTo(1);
        assertThat(range.neverArrived()).isEqualTo(1);
        assertThat(range.cancellations()).isEqualTo(1);
        assertThat(range.noShowRate()).isEqualTo(0.5);
        assertThat(range.byDay()).containsExactly(
                new NoShowResponse.Day(twoDaysAgo, 2, 0, 1),
                new NoShowResponse.Day(TODAY, 1, 1, 0));

        assertThat(analytics.noShows(desk, twoDaysAgo, TODAY, drB.getId()).noShows()).isZero();
    }

    @Nested
    class Scoping {

        @Test
        void aDoctorGetsOnlyTheirOwnFigures() {
            scriptedDay();
            Actor doctorA = doctorActor(drA);

            DailySummaryResponse own = analytics.summary(doctorA, null, null);
            assertThat(own.doctorId()).isEqualTo(drA.getId());
            assertThat(own.patients()).isEqualTo(3);
            assertThat(own.scheduledAppointments()).isEqualTo(4);
            assertThat(own.noShowRate()).isEqualTo(0.25);
            assertThat(own.currentQueueLength()).isZero();

            assertThat(analytics.doctors(doctorA, null).doctors())
                    .extracting(DoctorAnalyticsResponse.Doctor::doctorId).containsExactly(drA.getId());
            assertThat(analytics.queue(doctorA, null, null).doctorId()).isEqualTo(drA.getId());
            assertThatThrownBy(() -> analytics.summary(doctorA, null, drB.getId()))
                    .isInstanceOf(ForbiddenException.class);
            assertThatThrownBy(() -> analytics.noShows(doctorA, null, null, drB.getId()))
                    .isInstanceOf(ForbiddenException.class);
        }

        @Test
        void frontDeskCanNarrowToOneDoctor() {
            scriptedDay();

            assertThat(analytics.summary(desk, null, drB.getId()).patients()).isEqualTo(2);
            assertThat(analytics.waitTimes(desk, null, drB.getId()).calledPatients()).isEqualTo(1);
            assertThat(analytics.queue(desk, null, drB.getId()).currentQueueLength()).isEqualTo(1);
        }

        @Test
        void anotherClinicSeesNothingAndCannotNameOurDoctors() {
            scriptedDay();
            Clinic other = clinic("Other Clinic");
            Actor otherDesk = frontDesk(other);

            assertThat(analytics.summary(otherDesk, TODAY, null).patients()).isZero();
            assertThat(analytics.summary(otherDesk, TODAY, null).scheduledAppointments()).isZero();
            assertThat(analytics.doctors(otherDesk, TODAY).doctors()).isEmpty();
            assertThat(analytics.noShows(otherDesk, null, null, null).noShows()).isZero();
            assertThatThrownBy(() -> analytics.summary(otherDesk, TODAY, drA.getId()))
                    .isInstanceOf(NotFoundException.class);
        }

        @Test
        void overHttpTheClinicComesFromTheSessionOnly() throws Exception {
            scriptedDay();
            Clinic other = clinic("Other Clinic");
            String otherDesk = login(staff(other, Role.ADMIN, null, "admin@other.test"));
            String admin = login(staff(clinic, Role.ADMIN, null, "admin@city.test"));

            mvc.perform(get("/api/v1/analytics/today").with(bearer(admin)))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.patients").value(5))
                    .andExpect(jsonPath("$.medianWaitSeconds").value(2100.0));
            mvc.perform(get("/api/v1/analytics/today").param("clinicId", clinic.getId().toString())
                            .with(bearer(otherDesk)))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.patients").value(0));
            mvc.perform(get("/api/v1/analytics/doctors").with(bearer(otherDesk)))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.doctors").isEmpty());
            mvc.perform(get("/api/v1/analytics/wait-times").param("doctorId", drA.getId().toString())
                            .with(bearer(otherDesk)))
                    .andExpect(status().isNotFound());
        }

        @Test
        void overHttpADoctorIsLimitedToThemselves() throws Exception {
            scriptedDay();
            String doctorA = login(staff(clinic, Role.DOCTOR, drA, "a@city.test"));
            String receptionist = login(staff(clinic, Role.RECEPTIONIST, null, "desk@city.test"));

            mvc.perform(get("/api/v1/analytics/today").with(bearer(doctorA)))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.doctorId").value(drA.getId().toString()))
                    .andExpect(jsonPath("$.patients").value(3));
            mvc.perform(get("/api/v1/analytics/queue").param("doctorId", drB.getId().toString()).with(bearer(doctorA)))
                    .andExpect(status().isForbidden());
            mvc.perform(get("/api/v1/analytics/doctors").with(bearer(doctorA)))
                    .andExpect(jsonPath("$.doctors.length()").value(1));
            mvc.perform(get("/api/v1/analytics/doctors").with(bearer(receptionist)))
                    .andExpect(jsonPath("$.doctors.length()").value(2));
            mvc.perform(get("/api/v1/analytics/today"))
                    .andExpect(status().isUnauthorized());
        }

        @Test
        void invalidRangesAreRejected() throws Exception {
            String admin = login(staff(clinic, Role.ADMIN, null, "admin@city.test"));

            mvc.perform(get("/api/v1/analytics/no-shows").param("from", "2026-03-10").param("to", "2026-03-01")
                            .with(bearer(admin)))
                    .andExpect(status().isBadRequest());
            mvc.perform(get("/api/v1/analytics/no-shows").param("from", "2025-01-01").param("to", "2026-03-10")
                            .with(bearer(admin)))
                    .andExpect(status().isBadRequest());
            mvc.perform(get("/api/v1/analytics/today").param("date", "yesterday").with(bearer(admin)))
                    .andExpect(status().isBadRequest());
        }
    }
}
