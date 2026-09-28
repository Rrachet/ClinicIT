package com.clinicit.demo;

import com.clinicit.analytics.application.AnalyticsService;
import com.clinicit.appointment.application.AppointmentService;
import com.clinicit.clinic.application.ClinicTime;
import com.clinicit.clinic.domain.ClinicRepository;
import com.clinicit.clinic.domain.DoctorProfileRepository;
import com.clinicit.identity.domain.Actor;
import com.clinicit.identity.domain.Role;
import com.clinicit.patient.domain.PatientRepository;
import com.clinicit.queue.application.QueueService;
import com.clinicit.schedule.application.DoctorScheduleService;
import com.clinicit.support.PostgresIntegrationTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.DefaultApplicationArguments;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** The demo clinic loads completely, obeys every invariant, and feeds the product's screens. */
class DemoDataIntegrationTest extends PostgresIntegrationTest {

    static final String DEMO_PASSWORD = "demo-password-for-tests";

    @Autowired ClinicRepository clinicRepository;
    @Autowired DoctorProfileRepository doctorRepository;
    @Autowired PatientRepository patientRepository;
    @Autowired AppointmentService appointmentService;
    @Autowired QueueService queueService;
    @Autowired DoctorScheduleService scheduleService;
    @Autowired ClinicTime clinicTime;
    @Autowired TransactionTemplate tx;
    @Autowired AnalyticsService analytics;

    DemoData demo;

    DemoData demo(DemoProperties properties) {
        return new DemoData(properties, clinicRepository, doctorRepository, patientRepository, users, passwordEncoder,
                appointmentService, queueService, scheduleService, clinicTime, jdbc, tx);
    }

    @BeforeEach
    void setUp() {
        demo = demo(new DemoProperties(true, DEMO_PASSWORD, 14));
    }

    private UUID clinicId() {
        return jdbc.queryForObject("select id from clinics", UUID.class);
    }

    @Test
    void loadsTheDemoClinicWithWorkingLoginsFictionalPatientsAndSchedules() {
        demo.run(new DefaultApplicationArguments());

        assertThat(jdbc.queryForMap("select name, timezone from clinics"))
                .containsEntry("name", "ClinicIT Demo Clinic, Hyderabad").containsEntry("timezone", "Asia/Kolkata");
        for (String email : List.of("admin@demo.clinicit.local", "reception@demo.clinicit.local",
                "ananya.reddy@demo.clinicit.local", "farhan.siddiqui@demo.clinicit.local", "kavya.iyer@demo.clinicit.local")) {
            assertThat(authService.login(email, DEMO_PASSWORD, "127.0.0.1").accessToken()).as(email).isNotBlank();
        }
        assertThat(jdbc.queryForObject("select count(*) from patients", Integer.class)).isEqualTo(18);
        assertThat(jdbc.queryForList("select phone from patients", String.class))
                .allMatch(phone -> phone.startsWith("+91 90000 000"));
        // Mon-Sat 09:00-18:00, lunch 13:00-14:00, for each of the three doctors.
        assertThat(jdbc.queryForObject("select count(*) from doctor_working_hours", Integer.class)).isEqualTo(18);
        assertThat(jdbc.queryForObject("select count(*) from doctor_working_hours where day_of_week = 7", Integer.class)).isZero();
    }

    @Test
    void todaysQueueHasPatientsInEveryState() {
        demo.run(new DefaultApplicationArguments());

        Map<String, Long> today = jdbc.queryForList(
                        "select status from appointments where scheduled_at::date = ?", String.class, TODAY)
                .stream().collect(Collectors.groupingBy(s -> s, Collectors.counting()));
        assertThat(today.keySet()).contains("BOOKED", "CONFIRMED", "ARRIVED", "WAITING", "CALLED",
                "IN_CONSULTATION", "SKIPPED", "COMPLETED", "CANCELLED", "NO_SHOW");

        // Reception's and the doctors' screens work on it: boards, estimates, one active patient per doctor.
        Actor desk = new Actor(UUID.randomUUID(), clinicId(), Role.RECEPTIONIST, null);
        UUID reddy = jdbc.queryForObject("select id from doctor_profiles where display_name = 'Dr. Ananya Reddy'", UUID.class);
        var board = queueService.todayForDoctor(desk, reddy);
        assertThat(board.entries()).extracting(e -> e.status().name())
                .contains("IN_CONSULTATION", "WAITING", "SKIPPED", "COMPLETED");
        var estimates = waitTimePredictions.forDoctorToday(desk, reddy).entries();
        assertThat(estimates).hasSize(2).allSatisfy(e -> assertThat(e.estimatedWaitMinutes()).isNotNegative());
    }

    @Test
    void historyCoversTwoWeeksOfClinicDaysInTimeOrderAndFeedsTheTrends() {
        demo.run(new DefaultApplicationArguments());

        List<LocalDate> days = jdbc.queryForList(
                "select distinct queue_date from queue_entries where queue_date < ? order by 1", LocalDate.class, TODAY);
        assertThat(days).hasSize(14).noneMatch(d -> d.getDayOfWeek() == DayOfWeek.SUNDAY);
        // Sequence numbers follow time, as they do for live activity.
        assertThat(jdbc.queryForObject("""
                select count(*) from (
                    select occurred_at, lag(occurred_at) over (order by seq) as previous from operational_events
                ) ordered where occurred_at < previous""", Integer.class)).isZero();
        // Tokens run 1..n per clinic day without gaps.
        assertThat(jdbc.queryForObject("""
                select count(*) from (
                    select queue_date, max(token_number) as top, count(*) as n from queue_entries group by queue_date
                ) t where top <> n""", Integer.class)).isZero();

        Actor admin = new Actor(UUID.randomUUID(), clinicId(), Role.ADMIN, null);
        var trends = analytics.trends(admin, null, null, null);
        assertThat(trends.days()).filteredOn(d -> d.date().getDayOfWeek() != DayOfWeek.SUNDAY && d.date().isBefore(TODAY))
                .allSatisfy(d -> {
                    assertThat(d.completed()).isPositive();
                    assertThat(d.medianWaitSeconds()).isNotNull();
                    assertThat(d.completionRate()).isBetween(0.0, 1.0);
                });
        assertThat(trends.doctors()).hasSize(3).allSatisfy(d -> assertThat(d.completed()).isPositive());
    }

    @Test
    void itOnlyLoadsIntoAnEmptyDatabaseAndNeedsAPassword() {
        clinic("Existing Clinic");
        demo.run(new DefaultApplicationArguments());
        assertThat(jdbc.queryForObject("select count(*) from clinics", Integer.class)).isEqualTo(1);

        assertThatThrownBy(() -> demo(new DemoProperties(true, "short", 14)).run(new DefaultApplicationArguments()))
                .hasMessageContaining("CLINICIT_DEMO_PASSWORD");
        demo(new DemoProperties(false, null, 14)).run(new DefaultApplicationArguments()); // disabled: nothing happens
    }
}
