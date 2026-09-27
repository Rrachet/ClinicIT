package com.clinicit.support;

import com.clinicit.appointment.domain.Appointment;
import com.clinicit.appointment.domain.AppointmentRepository;
import com.clinicit.appointment.domain.AppointmentStatus;
import com.clinicit.clinic.domain.Clinic;
import com.clinicit.clinic.domain.ClinicRepository;
import com.clinicit.clinic.domain.DoctorProfile;
import com.clinicit.clinic.domain.DoctorProfileRepository;
import com.clinicit.identity.application.AuthService;
import com.clinicit.identity.domain.Actor;
import com.clinicit.identity.domain.Role;
import com.clinicit.identity.domain.UserAccount;
import com.clinicit.identity.domain.UserAccountRepository;
import com.clinicit.patient.domain.Patient;
import com.clinicit.patient.domain.PatientRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.UUID;

/**
 * Full application against real PostgreSQL with the Flyway migrations applied and
 * {@code ddl-auto: validate}, so these tests also prove the schema matches the entities.
 *
 * <p>All subclasses share one configuration, so Spring caches a single context (and
 * connection pool) for the whole suite. Don't add per-class context customisations.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ExtendWith(PostgresAvailableCondition.class)
@Import(PostgresIntegrationTest.ClockConfig.class)
public abstract class PostgresIntegrationTest {

    /** 2026-03-10 11:00 in Asia/Kolkata (UTC+05:30). */
    protected static final Instant NOW = Instant.parse("2026-03-10T05:30:00Z");
    protected static final LocalDate TODAY = LocalDate.of(2026, 3, 10);
    protected static final String PASSWORD = "correct horse battery staple";

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", PostgresTestDatabase::url);
        registry.add("spring.datasource.username", PostgresTestDatabase::username);
        registry.add("spring.datasource.password", PostgresTestDatabase::password);
        registry.add("spring.datasource.hikari.maximum-pool-size", () -> "30");
    }

    @TestConfiguration
    static class ClockConfig {
        @Bean
        @Primary
        MutableClock testClock() {
            return new MutableClock(NOW);
        }
    }

    @Autowired protected MutableClock clock;
    @Autowired protected JdbcTemplate jdbc;
    @Autowired protected ClinicRepository clinics;
    @Autowired protected DoctorProfileRepository doctors;
    @Autowired protected PatientRepository patients;
    @Autowired protected AppointmentRepository appointments;
    @Autowired protected UserAccountRepository users;
    @Autowired protected AuthService authService;
    @Autowired protected PasswordEncoder passwordEncoder;

    @BeforeEach
    void resetDatabase() {
        clock.set(NOW);
        jdbc.execute("""
                truncate table auth_sessions, users, queue_token_counters, queue_entries,
                               appointments, patients, doctor_profiles, clinics cascade
                """);
    }

    protected Clinic clinic(String name) {
        Clinic clinic = new Clinic();
        clinic.setName(name);
        clinic.setTimezone("Asia/Kolkata");
        return clinics.save(clinic);
    }

    protected DoctorProfile doctor(Clinic clinic, String name) {
        DoctorProfile doctor = new DoctorProfile();
        doctor.setClinicId(clinic.getId());
        doctor.setDisplayName(name);
        return doctors.save(doctor);
    }

    protected Patient patient(Clinic clinic, String name) {
        Patient patient = new Patient();
        patient.setClinicId(clinic.getId());
        patient.setFullName(name);
        patient.setPhone("+91 90000 00000");
        return patients.save(patient);
    }

    /** An appointment today that has been confirmed and whose patient has arrived. */
    protected Appointment arrivedAppointment(DoctorProfile doctor, Patient patient) {
        return appointmentOn(doctor, patient, TODAY, AppointmentStatus.CONFIRMED, AppointmentStatus.ARRIVED);
    }

    protected Appointment appointmentOn(
            DoctorProfile doctor, Patient patient, LocalDate date, AppointmentStatus... path
    ) {
        Appointment appointment = new Appointment();
        appointment.setClinicId(doctor.getClinicId());
        appointment.setDoctorId(doctor.getId());
        appointment.setPatientId(patient.getId());
        appointment.setScheduledAt(LocalDateTime.of(date, LocalTime.of(16, 0)));
        for (AppointmentStatus status : path) {
            appointment.transitionTo(status);
        }
        return appointments.save(appointment);
    }

    /** A receptionist of the clinic, for calling services directly (no user row needed). */
    protected Actor frontDesk(Clinic clinic) {
        return new Actor(UUID.randomUUID(), clinic.getId(), Role.RECEPTIONIST, null);
    }

    protected Actor doctorActor(DoctorProfile doctor) {
        return new Actor(UUID.randomUUID(), doctor.getClinicId(), Role.DOCTOR, doctor.getId());
    }

    /** A real staff account with password {@link #PASSWORD}. */
    protected UserAccount staff(Clinic clinic, Role role, DoctorProfile doctor, String email) {
        return users.save(new UserAccount(
                clinic.getId(), email, passwordEncoder.encode(PASSWORD), email, role,
                doctor == null ? null : doctor.getId()));
    }

    protected String login(UserAccount user) {
        return authService.login(user.getEmail(), PASSWORD).accessToken();
    }

    protected static RequestPostProcessor bearer(String token) {
        return request -> {
            request.addHeader("Authorization", "Bearer " + token);
            return request;
        };
    }

    protected AppointmentStatus appointmentStatus(UUID appointmentId) {
        return appointments.findById(appointmentId).orElseThrow().getStatus();
    }
}
