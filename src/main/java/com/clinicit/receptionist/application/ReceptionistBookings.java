package com.clinicit.receptionist.application;

import com.clinicit.appointment.application.AppointmentService;
import com.clinicit.appointment.domain.Appointment;
import com.clinicit.checkin.application.SelfCheckInService;
import com.clinicit.checkin.domain.VisitType;
import com.clinicit.checkin.domain.VisitTypeRepository;
import com.clinicit.clinic.application.ClinicTime;
import com.clinicit.clinic.domain.DoctorProfile;
import com.clinicit.clinic.domain.DoctorProfileRepository;
import com.clinicit.common.domain.BusinessRuleException;
import com.clinicit.common.domain.InvalidRequestException;
import com.clinicit.common.domain.NotFoundException;
import com.clinicit.patient.domain.Patient;
import com.clinicit.schedule.api.AvailabilityResponse;
import com.clinicit.schedule.application.AvailabilityService;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;

/**
 * What the AI receptionist may do in a clinic, under the same rules as the rest of ClinicIT:
 * find free slots for a visit type and book one. The model only ever proposes; these methods
 * decide (docs/AI_RECEPTIONIST.md).
 */
@Service
@Transactional
public class ReceptionistBookings {

    /** How far ahead patients may book through the chat. */
    public static final int DAYS_AHEAD = 7;
    private static final int MAX_SLOTS = 12;

    private final VisitTypeRepository visitTypes;
    private final DoctorProfileRepository doctors;
    private final AvailabilityService availability;
    private final AppointmentService appointments;
    private final SelfCheckInService checkIn;
    private final ClinicTime clinicTime;
    private final JdbcTemplate jdbc;

    public ReceptionistBookings(
            VisitTypeRepository visitTypes,
            DoctorProfileRepository doctors,
            AvailabilityService availability,
            AppointmentService appointments,
            SelfCheckInService checkIn,
            ClinicTime clinicTime,
            JdbcTemplate jdbc
    ) {
        this.visitTypes = visitTypes;
        this.doctors = doctors;
        this.availability = availability;
        this.appointments = appointments;
        this.checkIn = checkIn;
        this.clinicTime = clinicTime;
        this.jdbc = jdbc;
    }

    public record SlotOption(UUID doctorId, String doctorName, String specialization, LocalDateTime start) {}

    public record Booked(LocalDateTime start, String doctorName, String room) {}

    /** Free slots on one day for the doctors who see this visit type, earliest first. */
    @Transactional(readOnly = true)
    public List<SlotOption> freeSlots(UUID clinicId, UUID visitTypeId, LocalDate date) {
        VisitType type = activeVisitType(clinicId, visitTypeId);
        requireBookableDate(clinicId, date);
        return allFreeSlots(clinicId, type, date).stream().limit(MAX_SLOTS).toList();
    }

    private List<SlotOption> allFreeSlots(UUID clinicId, VisitType type, LocalDate date) {
        List<SlotOption> options = new ArrayList<>();
        for (DoctorProfile doctor : doctorsFor(clinicId, type)) {
            AvailabilityResponse day = availability.forDay(clinicId, doctor.getId(), date);
            day.slots().stream()
                    .filter(AvailabilityResponse.Slot::available)
                    .forEach(slot -> options.add(new SlotOption(doctor.getId(), doctor.getDisplayName(),
                            doctor.getSpecialization(), slot.start())));
        }
        return options.stream()
                .sorted(Comparator.comparing(SlotOption::start).thenComparing(SlotOption::doctorName))
                .toList();
    }

    /** Books a free slot with a doctor who sees this visit type. One booking per patient per day. */
    public Booked book(UUID clinicId, UUID visitTypeId, UUID doctorId, LocalDateTime start,
                       String fullName, String phone, String note) {
        VisitType type = activeVisitType(clinicId, visitTypeId);
        requireBookableDate(clinicId, start.toLocalDate());
        DoctorProfile doctor = doctorsFor(clinicId, type).stream()
                .filter(d -> d.getId().equals(doctorId))
                .findFirst()
                .orElseThrow(() -> new InvalidRequestException("doctor_id: this doctor does not see that visit type"));
        // Only a slot find_slots would offer: on the doctor's grid, free, and in the future.
        LocalDateTime slotStart = start.truncatedTo(java.time.temporal.ChronoUnit.MINUTES);
        boolean offered = allFreeSlots(clinicId, type, slotStart.toLocalDate()).stream()
                .anyMatch(slot -> slot.doctorId().equals(doctorId) && slot.start().equals(slotStart));
        if (!offered) {
            throw new BusinessRuleException("SLOT_NOT_FREE",
                    "That time is not a free slot with this doctor. Use a time from find_slots.");
        }
        Patient patient = checkIn.patientFor(clinicId, fullName, phone);
        List<LocalDateTime> existing = jdbc.queryForList("""
                select scheduled_at from appointments
                where clinic_id = ? and patient_id = ? and status in ('BOOKED', 'CONFIRMED')
                  and scheduled_at >= ? and scheduled_at < ?
                """, LocalDateTime.class, clinicId, patient.getId(),
                start.toLocalDate().atStartOfDay(), start.toLocalDate().plusDays(1).atStartOfDay());
        if (!existing.isEmpty()) {
            throw new BusinessRuleException("ALREADY_BOOKED",
                    "This patient already has an appointment that day at " + existing.get(0).toLocalTime());
        }
        String reason = note == null || note.isBlank() ? type.getName() : type.getName() + ": " + note.strip();
        Appointment booked = appointments.createSelfBooked(clinicId, patient.getId(), doctor.getId(), type.getId(),
                start, reason.length() > 500 ? reason.substring(0, 500) : reason);
        return new Booked(booked.getScheduledAt(), doctor.getDisplayName(), doctor.getRoom());
    }

    private VisitType activeVisitType(UUID clinicId, UUID visitTypeId) {
        return visitTypes.findByIdAndClinicId(visitTypeId, clinicId)
                .filter(VisitType::isActive)
                .orElseThrow(() -> new NotFoundException("Visit type not found"));
    }

    private void requireBookableDate(UUID clinicId, LocalDate date) {
        LocalDate today = clinicTime.today(clinicId);
        if (date.isBefore(today) || date.isAfter(today.plusDays(DAYS_AHEAD))) {
            throw new InvalidRequestException("date: bookings are possible from today up to " + DAYS_AHEAD + " days ahead");
        }
    }

    private List<DoctorProfile> doctorsFor(UUID clinicId, VisitType type) {
        List<DoctorProfile> all = doctors.findByClinicIdOrderByDisplayNameAsc(clinicId);
        if (type.isAnyDoctor()) {
            return all;
        }
        List<UUID> linked = jdbc.queryForList(
                "select doctor_id from visit_type_doctors where clinic_id = ? and visit_type_id = ?",
                UUID.class, clinicId, type.getId());
        return all.stream().filter(d -> linked.contains(d.getId())).toList();
    }
}
