package com.clinicit.checkin.application;

import com.clinicit.appointment.application.AppointmentService;
import com.clinicit.appointment.domain.Appointment;
import com.clinicit.checkin.api.PublicCheckInResponse;
import com.clinicit.checkin.api.SelfCheckInRequest;
import com.clinicit.checkin.api.SelfCheckInResponse;
import com.clinicit.checkin.domain.VisitType;
import com.clinicit.checkin.domain.VisitTypeRepository;
import com.clinicit.clinic.application.ClinicTime;
import com.clinicit.clinic.domain.Clinic;
import com.clinicit.clinic.domain.ClinicRepository;
import com.clinicit.clinic.domain.DoctorProfile;
import com.clinicit.clinic.domain.DoctorProfileRepository;
import com.clinicit.common.domain.BusinessRuleException;
import com.clinicit.common.domain.InvalidRequestException;
import com.clinicit.common.domain.NotFoundException;
import com.clinicit.patient.domain.Patient;
import com.clinicit.patient.domain.PatientRepository;
import com.clinicit.queue.api.QueueEntryResponse;
import com.clinicit.queue.application.QueueService;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * Self check-in: a patient scans the clinic's QR code, enters their name, phone and visit
 * type, and is queued with a live doctor who sees that visit type, with no receptionist.
 *
 * <p>The doctor is chosen by rules, never by reading what the patient wrote about their
 * health: among live doctors who see the visit type, the one with the fewest patients
 * waiting or with them, then whoever went live first. See docs/SELF_CHECK_IN.md.
 */
@Service
@Transactional
public class SelfCheckInService {

    private static final Pattern PHONE = Pattern.compile("\\+?\\d{7,15}");

    private final ClinicRepository clinics;
    private final VisitTypeRepository visitTypes;
    private final DoctorProfileRepository doctors;
    private final PatientRepository patients;
    private final AppointmentService appointments;
    private final QueueService queue;
    private final ClinicTime clinicTime;
    private final JdbcTemplate jdbc;

    public SelfCheckInService(
            ClinicRepository clinics,
            VisitTypeRepository visitTypes,
            DoctorProfileRepository doctors,
            PatientRepository patients,
            AppointmentService appointments,
            QueueService queue,
            ClinicTime clinicTime,
            JdbcTemplate jdbc
    ) {
        this.clinics = clinics;
        this.visitTypes = visitTypes;
        this.doctors = doctors;
        this.patients = patients;
        this.appointments = appointments;
        this.queue = queue;
        this.clinicTime = clinicTime;
        this.jdbc = jdbc;
    }

    @Transactional(readOnly = true)
    public PublicCheckInResponse page(String checkInCode) {
        Clinic clinic = clinic(checkInCode);
        LocalDate today = clinicTime.today(clinic.getId());
        Set<UUID> live = liveDoctors(clinic.getId(), today);
        List<PublicCheckInResponse.Option> options = visitTypes.findByClinicIdOrderBySortOrderAscNameAsc(clinic.getId())
                .stream()
                .filter(VisitType::isActive)
                .map(type -> new PublicCheckInResponse.Option(type.getId(), type.getName(), type.getDescription(),
                        !candidates(clinic.getId(), type, live).isEmpty()))
                .toList();
        return new PublicCheckInResponse(clinic.getName(), !live.isEmpty(), options);
    }

    public SelfCheckInResponse checkIn(String checkInCode, SelfCheckInRequest request) {
        Clinic clinic = clinic(checkInCode);
        UUID clinicId = clinic.getId();
        String name = request.fullName().strip().replaceAll("\\s+", " ");
        String phone = normalizePhone(request.phone());
        VisitType type = visitTypes.findByIdAndClinicId(request.visitTypeId(), clinicId)
                .filter(VisitType::isActive)
                .orElseThrow(() -> new NotFoundException("Visit type not found"));
        LocalDate today = clinicTime.today(clinicId);

        // Scanning again (or a second tap) returns the place they already have.
        Optional<Patient> known = patients.findFirstByClinicIdAndPhoneAndFullNameIgnoreCase(clinicId, phone, name);
        if (known.isPresent()) {
            Optional<SelfCheckInResponse> existing = activeEntry(clinicId, known.get().getId(), today);
            if (existing.isPresent()) {
                return existing.get();
            }
        }

        // Booked ahead (for example through the AI receptionist): queue them for that booking.
        if (known.isPresent()) {
            Optional<SelfCheckInResponse> booked = arriveForBooking(clinicId, known.get().getId(), today);
            if (booked.isPresent()) {
                return booked.get();
            }
        }

        DoctorProfile doctor = chooseDoctor(clinicId, type, today);
        Patient patient = known.orElseGet(() -> createPatient(clinicId, name, phone));

        String reason = request.reason() == null || request.reason().isBlank() ? null : request.reason().strip();
        Appointment appointment = appointments.createSelfCheckIn(clinicId, patient.getId(), doctor.getId(), type.getId(),
                reason == null ? type.getName() : type.getName() + ": " + reason);
        QueueEntryResponse entry = queue.joinSelfCheckIn(clinicId, appointment.getId());
        return new SelfCheckInResponse(entry.statusCode(), entry.tokenNumber(), doctor.getDisplayName(), doctor.getRoom(),
                false);
    }

    /**
     * The patient with this name and phone in the clinic, or a new patient record. Spaces,
     * dashes and brackets in the phone are ignored; names match regardless of case.
     */
    public Patient patientFor(UUID clinicId, String fullName, String rawPhone) {
        String name = fullName.strip().replaceAll("\\s+", " ");
        String phone = normalizePhone(rawPhone);
        return patients.findFirstByClinicIdAndPhoneAndFullNameIgnoreCase(clinicId, phone, name)
                .orElseGet(() -> createPatient(clinicId, name, phone));
    }

    private Patient createPatient(UUID clinicId, String name, String phone) {
        Patient created = new Patient();
        created.setClinicId(clinicId);
        created.setFullName(name);
        created.setPhone(phone);
        return patients.save(created);
    }

    /** Today's earliest booked or confirmed appointment of this patient becomes arrived and queued. */
    private Optional<SelfCheckInResponse> arriveForBooking(UUID clinicId, UUID patientId, LocalDate today) {
        List<UUID> bookings = jdbc.queryForList("""
                select id from appointments
                where clinic_id = ? and patient_id = ? and not walk_in and status in ('BOOKED', 'CONFIRMED')
                  and scheduled_at >= ? and scheduled_at < ?
                order by scheduled_at
                limit 1
                """, UUID.class, clinicId, patientId, today.atStartOfDay(), today.plusDays(1).atStartOfDay());
        if (bookings.isEmpty()) {
            return Optional.empty();
        }
        Appointment appointment = appointments.arriveSelfCheckIn(clinicId, bookings.get(0));
        DoctorProfile doctor = doctors.findByIdAndClinicId(appointment.getDoctorId(), clinicId)
                .orElseThrow(() -> new NotFoundException("Doctor not found"));
        QueueEntryResponse entry = queue.joinSelfCheckIn(clinicId, appointment.getId());
        return Optional.of(new SelfCheckInResponse(entry.statusCode(), entry.tokenNumber(), doctor.getDisplayName(),
                doctor.getRoom(), false));
    }

    /**
     * The live doctor with the fewest patients waiting or with them, then whoever went live
     * first. The chosen doctor's row is locked and re-checked, so a doctor going offline at the
     * same moment does not receive the patient.
     */
    private DoctorProfile chooseDoctor(UUID clinicId, VisitType type, LocalDate today) {
        List<UUID> ranked = jdbc.queryForList("""
                select d.id
                from doctor_profiles d
                left join queue_entries q
                       on q.doctor_id = d.id and q.queue_date = ? and q.status in ('WAITING', 'CALLED', 'IN_CONSULTATION')
                where d.clinic_id = ? and d.on_duty_date = ?
                  and (? or exists (select 1 from visit_type_doctors v where v.visit_type_id = ? and v.doctor_id = d.id))
                group by d.id, d.on_duty_since
                order by count(q.id), d.on_duty_since nulls last, d.id
                """, UUID.class, today, clinicId, today, type.isAnyDoctor(), type.getId());
        for (UUID id : ranked) {
            Optional<DoctorProfile> doctor = doctors.findByIdAndClinicIdForUpdate(id, clinicId)
                    .filter(found -> found.isOnDuty(today));
            if (doctor.isPresent()) {
                return doctor.get();
            }
        }
        throw new BusinessRuleException("NO_DOCTOR_AVAILABLE",
                "No doctor for this visit is available right now. Please choose another visit or ask at the clinic.");
    }

    private Optional<SelfCheckInResponse> activeEntry(UUID clinicId, UUID patientId, LocalDate today) {
        return jdbc.query("""
                select q.status_code, q.token_number, d.display_name, d.room
                from queue_entries q
                join appointments a on a.id = q.appointment_id
                join doctor_profiles d on d.id = q.doctor_id
                where q.clinic_id = ? and a.patient_id = ? and q.queue_date = ?
                  and q.status in ('WAITING', 'CALLED', 'IN_CONSULTATION', 'SKIPPED')
                order by q.token_number desc
                limit 1
                """, (rs, i) -> new SelfCheckInResponse(rs.getString(1), rs.getInt(2), rs.getString(3), rs.getString(4), true),
                clinicId, patientId, today).stream().findFirst();
    }

    private Set<UUID> liveDoctors(UUID clinicId, LocalDate today) {
        return new HashSet<>(jdbc.queryForList(
                "select id from doctor_profiles where clinic_id = ? and on_duty_date = ?", UUID.class, clinicId, today));
    }

    private Set<UUID> candidates(UUID clinicId, VisitType type, Set<UUID> live) {
        if (type.isAnyDoctor()) return live;
        Set<UUID> linked = new HashSet<>(jdbc.queryForList(
                "select doctor_id from visit_type_doctors where clinic_id = ? and visit_type_id = ?",
                UUID.class, clinicId, type.getId()));
        linked.retainAll(live);
        return linked;
    }

    private Clinic clinic(String checkInCode) {
        if (checkInCode == null || !checkInCode.matches("[A-Za-z0-9_-]{8,32}")) {
            throw new NotFoundException("Clinic not found");
        }
        return clinics.findByCheckInCode(checkInCode).orElseThrow(() -> new NotFoundException("Clinic not found"));
    }

    /** Spaces, dashes, dots and brackets are dropped; what is left must look like a phone number. */
    static String normalizePhone(String raw) {
        String phone = raw.strip().replaceAll("[\\s().-]", "");
        if (!PHONE.matcher(phone).matches()) {
            throw new InvalidRequestException("phone: enter a phone number of 7 to 15 digits");
        }
        return phone;
    }
}
