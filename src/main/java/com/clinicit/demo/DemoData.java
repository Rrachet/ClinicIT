package com.clinicit.demo;

import com.clinicit.appointment.api.AppointmentResponse;
import com.clinicit.appointment.api.CreateAppointmentRequest;
import com.clinicit.appointment.application.AppointmentService;
import com.clinicit.clinic.application.ClinicTime;
import com.clinicit.clinic.domain.Clinic;
import com.clinicit.clinic.domain.ClinicRepository;
import com.clinicit.clinic.domain.DoctorProfile;
import com.clinicit.clinic.domain.DoctorProfileRepository;
import com.clinicit.identity.domain.Actor;
import com.clinicit.identity.domain.Role;
import com.clinicit.identity.domain.UserAccount;
import com.clinicit.identity.domain.UserAccountRepository;
import com.clinicit.patient.domain.Patient;
import com.clinicit.patient.domain.PatientRepository;
import com.clinicit.queue.api.QueueEntryResponse;
import com.clinicit.queue.application.QueueService;
import com.clinicit.schedule.application.DoctorScheduleService;
import com.clinicit.schedule.domain.WorkingHours;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.core.annotation.Order;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Loads the demo clinic on an empty database when {@code clinicit.demo.enabled=true}: staff
 * logins, three doctors with weekly schedules, fictional patients, two weeks of finished
 * visits (so trends, utilization and wait estimates have history), and today's queue with
 * patients in every state. All in one transaction: it loads completely or not at all.
 *
 * <p>Today's live state goes through the real appointment and queue services, exactly as
 * reception would create it. Past visits are written by {@link DemoHistory} with their
 * historical timestamps. Names and phone numbers are invented. The prod profile refuses to
 * start with demo data enabled ({@code ProductionConfigurationCheck}).
 */
@Component
@Order(0)
@EnableConfigurationProperties(DemoProperties.class)
public class DemoData implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(DemoData.class);

    static final String CLINIC_NAME = "ClinicIT Demo Clinic, Hyderabad";
    static final String ZONE = "Asia/Kolkata";
    static final String ADMIN_EMAIL = "admin@demo.clinicit.local";
    static final String RECEPTION_EMAIL = "reception@demo.clinicit.local";

    private record DoctorSpec(String name, String specialization, int minutes, String email) {}

    static final List<String> PATIENTS = List.of(
            "Aarav Mehta", "Diya Nair", "Ishaan Rao", "Meera Pillai", "Kabir Khan", "Ananya Gupta",
            "Rohan Das", "Sara Thomas", "Vihaan Joshi", "Zoya Ahmed", "Arjun Menon", "Nisha Verma",
            "Tanvi Kulkarni", "Imran Qureshi", "Lakshmi Iyer", "Dev Malhotra", "Fatima Shaikh", "Kiran Babu");

    private static final List<DoctorSpec> DOCTORS = List.of(
            new DoctorSpec("Dr. Ananya Reddy", "General Medicine", 10, "ananya.reddy@demo.clinicit.local"),
            new DoctorSpec("Dr. Farhan Siddiqui", "Paediatrics", 15, "farhan.siddiqui@demo.clinicit.local"),
            new DoctorSpec("Dr. Kavya Iyer", "Dermatology", 20, "kavya.iyer@demo.clinicit.local"));

    private final DemoProperties properties;
    private final ClinicRepository clinics;
    private final DoctorProfileRepository doctors;
    private final PatientRepository patients;
    private final UserAccountRepository users;
    private final PasswordEncoder passwordEncoder;
    private final AppointmentService appointments;
    private final QueueService queue;
    private final DoctorScheduleService schedules;
    private final ClinicTime clinicTime;
    private final JdbcTemplate jdbc;
    private final TransactionTemplate transactions;

    public DemoData(DemoProperties properties, ClinicRepository clinics, DoctorProfileRepository doctors,
                    PatientRepository patients, UserAccountRepository users, PasswordEncoder passwordEncoder,
                    AppointmentService appointments, QueueService queue, DoctorScheduleService schedules,
                    ClinicTime clinicTime, JdbcTemplate jdbc, TransactionTemplate transactions) {
        this.properties = properties;
        this.clinics = clinics;
        this.doctors = doctors;
        this.patients = patients;
        this.users = users;
        this.passwordEncoder = passwordEncoder;
        this.appointments = appointments;
        this.queue = queue;
        this.schedules = schedules;
        this.clinicTime = clinicTime;
        this.jdbc = jdbc;
        this.transactions = transactions;
    }

    @Override
    public void run(ApplicationArguments args) {
        if (!properties.enabled()) return;
        if (properties.password() == null || properties.password().length() < 12) {
            throw new IllegalStateException("clinicit.demo.password (CLINICIT_DEMO_PASSWORD) must be set, 12+ characters");
        }
        if (clinics.count() > 0) {
            log.info("Demo data not loaded: the database already has a clinic");
            return;
        }
        transactions.executeWithoutResult(status -> load());
        log.info("Loaded the demo clinic '{}'", CLINIC_NAME);
    }

    void load() {
        Clinic clinic = new Clinic();
        clinic.setName(CLINIC_NAME);
        clinic.setTimezone(ZONE);
        clinic = clinics.save(clinic);
        UUID clinicId = clinic.getId();
        String hash = passwordEncoder.encode(properties.password());

        UserAccount admin = users.save(new UserAccount(clinicId, ADMIN_EMAIL, hash, "Priya Sharma", Role.ADMIN, null));
        users.save(new UserAccount(clinicId, RECEPTION_EMAIL, hash, "Ravi Kumar", Role.RECEPTIONIST, null));
        List<DoctorProfile> team = new ArrayList<>();
        for (DoctorSpec spec : DOCTORS) {
            DoctorProfile doctor = new DoctorProfile();
            doctor.setClinicId(clinicId);
            doctor.setDisplayName(spec.name());
            doctor.setSpecialization(spec.specialization());
            doctor.setAppointmentMinutes(spec.minutes());
            doctor = doctors.save(doctor);
            team.add(doctor);
            users.save(new UserAccount(clinicId, spec.email(), hash, spec.name(), Role.DOCTOR, doctor.getId()));
        }

        List<UUID> patientIds = new ArrayList<>();
        for (int i = 0; i < PATIENTS.size(); i++) {
            Patient patient = new Patient();
            patient.setClinicId(clinicId);
            patient.setFullName(PATIENTS.get(i));
            // Fictional numbers in one obviously fake block.
            patient.setPhone("+91 90000 000%02d".formatted(i + 1));
            patient.setDateOfBirth(LocalDate.of(1950 + (i * 7) % 60, 1 + i % 12, 1 + (i * 3) % 28));
            patientIds.add(patients.save(patient).getId());
        }
        doctors.flush();
        patients.flush();
        users.flush();

        ZoneId zone = ZoneId.of(ZONE);
        LocalDateTime now = clinicTime.now(clinicId).truncatedTo(ChronoUnit.MINUTES);
        LocalDate today = now.toLocalDate();
        List<DemoHistory.Doctor> historyDoctors = new ArrayList<>();
        for (int i = 0; i < team.size(); i++) {
            historyDoctors.add(new DemoHistory.Doctor(team.get(i).getId(), DOCTORS.get(i).minutes()));
        }

        // Past clinic days (Monday to Saturday), then today's finished visits, all before any live activity.
        DemoHistory history = new DemoHistory(jdbc, clinicId, admin.getId(), zone, 20260928L);
        int pastDays = 0;
        for (LocalDate day = today.minusDays(1); pastDays < properties.historyDays(); day = day.minusDays(1)) {
            if (day.getDayOfWeek() == DayOfWeek.SUNDAY) continue;
            history.setLastToken(day, history.day(day, historyDoctors, patientIds, day.atTime(23, 0)));
            pastDays++;
        }
        // Today's morning, up to half an hour ago (nothing if the clinic has not opened yet).
        int todaysTokens = history.day(today, historyDoctors, patientIds, now.minusMinutes(30));
        history.flushEvents();
        history.setLastToken(today, todaysTokens);

        liveQueue(new Actor(admin.getId(), clinicId, Role.ADMIN, null), team, patientIds, now);

        // Schedules last: they apply to new bookings, not to the history above.
        Actor adminActor = new Actor(admin.getId(), clinicId, Role.ADMIN, null);
        for (DoctorProfile doctor : team) {
            List<WorkingHours> week = new ArrayList<>();
            for (DayOfWeek day : DayOfWeek.values()) {
                if (day == DayOfWeek.SUNDAY) continue;
                week.add(new WorkingHours(day, LocalTime.of(9, 0), LocalTime.of(18, 0), LocalTime.of(13, 0), LocalTime.of(14, 0)));
            }
            schedules.replaceWeek(adminActor, doctor.getId(), doctor.getAppointmentMinutes(), week);
        }
    }

    /** Today's queue with patients in every state, created through the same services as reception. */
    private void liveQueue(Actor desk, List<DoctorProfile> team, List<UUID> patientIds, LocalDateTime now) {
        DoctorProfile reddy = team.get(0);
        DoctorProfile siddiqui = team.get(1);
        DoctorProfile iyer = team.get(2);
        int next = 0;

        // Dr. Reddy: one with the doctor, two waiting, one who missed their call.
        QueueEntryResponse inConsultation = checkIn(desk, reddy, patientIds.get(next++), now.minusMinutes(25));
        QueueEntryResponse skipped = checkIn(desk, reddy, patientIds.get(next++), now.minusMinutes(20));
        checkIn(desk, reddy, patientIds.get(next++), now.minusMinutes(12));
        checkIn(desk, reddy, patientIds.get(next++), now.minusMinutes(5));
        queue.callNext(desk, reddy.getId());
        queue.startConsultation(desk, inConsultation.id());
        queue.skip(desk, skipped.id());

        // Dr. Siddiqui: one called, two waiting.
        checkIn(desk, siddiqui, patientIds.get(next++), now.minusMinutes(18));
        checkIn(desk, siddiqui, patientIds.get(next++), now.minusMinutes(9));
        checkIn(desk, siddiqui, patientIds.get(next++), now.minusMinutes(2));
        queue.callNext(desk, siddiqui.getId());

        // Dr. Iyer: one waiting; one arrived at the desk, not yet in the queue.
        checkIn(desk, iyer, patientIds.get(next++), now.minusMinutes(7));
        AppointmentResponse arrived = book(desk, iyer, patientIds.get(next++), now.minusMinutes(3));
        appointments.confirm(desk, arrived.id());
        appointments.arrive(desk, arrived.id());

        // Not here yet, cancelled and missed.
        AppointmentResponse confirmed = book(desk, reddy, patientIds.get(next++), now.plusMinutes(40));
        appointments.confirm(desk, confirmed.id());
        book(desk, siddiqui, patientIds.get(next++), now.plusMinutes(75));
        book(desk, iyer, patientIds.get(next++), now.plusMinutes(120));
        appointments.cancel(desk, book(desk, iyer, patientIds.get(next++), now.plusMinutes(60)).id());
        AppointmentResponse missed = book(desk, siddiqui, patientIds.get(next++), now.minusMinutes(50));
        appointments.confirm(desk, missed.id());
        appointments.markNoShow(desk, missed.id());
    }

    private AppointmentResponse book(Actor desk, DoctorProfile doctor, UUID patientId, LocalDateTime at) {
        return appointments.create(desk, new CreateAppointmentRequest(patientId, doctor.getId(), at, null));
    }

    private QueueEntryResponse checkIn(Actor desk, DoctorProfile doctor, UUID patientId, LocalDateTime at) {
        AppointmentResponse appointment = book(desk, doctor, patientId, at);
        appointments.confirm(desk, appointment.id());
        appointments.arrive(desk, appointment.id());
        return queue.join(desk, appointment.id());
    }
}
