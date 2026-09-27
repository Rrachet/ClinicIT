package com.clinicit.queue.application;

import com.clinicit.appointment.domain.Appointment;
import com.clinicit.appointment.domain.AppointmentRepository;
import com.clinicit.appointment.domain.AppointmentStatus;
import com.clinicit.clinic.domain.ClinicRepository;
import com.clinicit.clinic.domain.DoctorProfile;
import com.clinicit.clinic.domain.DoctorProfileRepository;
import com.clinicit.common.domain.BusinessRuleException;
import com.clinicit.common.domain.InvalidStateTransitionException;
import com.clinicit.common.domain.NotFoundException;
import com.clinicit.patient.domain.Patient;
import com.clinicit.patient.domain.PatientRepository;
import com.clinicit.queue.api.QueueBoardResponse;
import com.clinicit.queue.api.QueueEntryResponse;
import com.clinicit.queue.domain.QueueEntry;
import com.clinicit.queue.domain.QueueEntryRepository;
import com.clinicit.queue.domain.QueueStatus;
import com.clinicit.queue.domain.QueueTokenAllocator;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.BiConsumer;
import java.util.stream.Collectors;

/**
 * Queue engine. Every mutation runs in one transaction that updates the queue
 * entry and its appointment together, so the two never disagree.
 *
 * <p>Lock order, to rule out deadlocks: doctor → queue entry → appointment → token counter.
 * <ul>
 *   <li>join: appointment row, then the clinic/day token counter row</li>
 *   <li>call next: doctor row, then the next WAITING entry, then its appointment</li>
 *   <li>entry transitions: the entry row, then its appointment</li>
 * </ul>
 */
@Service
@Transactional
public class QueueService {

    private final QueueEntryRepository entries;
    private final QueueTokenAllocator tokens;
    private final AppointmentRepository appointments;
    private final DoctorProfileRepository doctors;
    private final ClinicRepository clinics;
    private final PatientRepository patients;
    private final Clock clock;

    public QueueService(
            QueueEntryRepository entries,
            QueueTokenAllocator tokens,
            AppointmentRepository appointments,
            DoctorProfileRepository doctors,
            ClinicRepository clinics,
            PatientRepository patients,
            Clock clock
    ) {
        this.entries = entries;
        this.tokens = tokens;
        this.appointments = appointments;
        this.doctors = doctors;
        this.clinics = clinics;
        this.patients = patients;
        this.clock = clock;
    }

    /** Puts an ARRIVED appointment into today's queue and issues its token. */
    public QueueEntryResponse join(UUID appointmentId) {
        Appointment appointment = appointments.findByIdForUpdate(appointmentId)
                .orElseThrow(() -> new NotFoundException("Appointment not found"));

        // Fail before touching the token counter.
        if (!appointment.getStatus().canTransitionTo(AppointmentStatus.WAITING)) {
            throw new InvalidStateTransitionException("appointment", appointment.getStatus(), AppointmentStatus.WAITING);
        }

        LocalDate today = today(appointment.getClinicId());
        if (!appointment.getScheduledAt().toLocalDate().equals(today)) {
            throw new BusinessRuleException("NOT_TODAY", "Only today's appointments can join the queue");
        }

        int token = tokens.nextToken(appointment.getClinicId(), today);
        QueueEntry entry = entries.save(QueueEntry.join(appointment, today, token, clock.instant()));
        appointment.transitionTo(AppointmentStatus.WAITING);

        return QueueEntryResponse.from(entry);
    }

    /**
     * Calls the waiting patient with the lowest token for this doctor.
     *
     * <p>A doctor has at most one active patient (CALLED or IN_CONSULTATION), so
     * the current patient must be completed or skipped first. The doctor row lock
     * makes the check-then-call atomic across concurrent callers.
     */
    public QueueEntryResponse callNext(UUID doctorId) {
        DoctorProfile doctor = doctors.findByIdForUpdate(doctorId)
                .orElseThrow(() -> new NotFoundException("Doctor not found"));
        LocalDate today = today(doctor.getClinicId());

        if (entries.existsByDoctorIdAndQueueDateAndStatusIn(doctorId, today, QueueStatus.ACTIVE)) {
            throw new BusinessRuleException("DOCTOR_BUSY",
                    "Doctor already has a patient called or in consultation");
        }

        QueueEntry next = entries.findNextWaitingForUpdate(doctorId, today)
                .orElseThrow(() -> new BusinessRuleException("QUEUE_EMPTY", "No patients waiting"));

        next.call(clock.instant());
        syncAppointment(next);
        return QueueEntryResponse.from(next);
    }

    public QueueEntryResponse startConsultation(UUID entryId) {
        return mutate(entryId, QueueEntry::startConsultation);
    }

    public QueueEntryResponse complete(UUID entryId) {
        return mutate(entryId, QueueEntry::complete);
    }

    public QueueEntryResponse skip(UUID entryId) {
        return mutate(entryId, QueueEntry::skip);
    }

    public QueueEntryResponse requeue(UUID entryId) {
        return mutate(entryId, QueueEntry::requeue);
    }

    public QueueEntryResponse markNoShow(UUID entryId) {
        return mutate(entryId, QueueEntry::markNoShow);
    }

    @Transactional(readOnly = true)
    public QueueEntryResponse get(UUID entryId) {
        return entries.findById(entryId)
                .map(QueueEntryResponse::from)
                .orElseThrow(() -> new NotFoundException("Queue entry not found"));
    }

    /** Today's queue for one doctor, in token order. For staff screens (includes patient names). */
    @Transactional(readOnly = true)
    public QueueBoardResponse todayForDoctor(UUID doctorId) {
        DoctorProfile doctor = doctors.findById(doctorId)
                .orElseThrow(() -> new NotFoundException("Doctor not found"));
        LocalDate today = today(doctor.getClinicId());

        List<QueueEntry> queue = entries.findByDoctorIdAndQueueDateOrderByTokenNumberAsc(doctorId, today);
        Map<UUID, String> names = patientNames(queue);

        return QueueBoardResponse.from(doctorId, today, queue, names);
    }

    private QueueEntryResponse mutate(UUID entryId, BiConsumer<QueueEntry, Instant> transition) {
        QueueEntry entry = entries.findByIdForUpdate(entryId)
                .orElseThrow(() -> new NotFoundException("Queue entry not found"));

        transition.accept(entry, clock.instant());
        syncAppointment(entry);
        return QueueEntryResponse.from(entry);
    }

    private void syncAppointment(QueueEntry entry) {
        Appointment appointment = appointments.findByIdForUpdate(entry.getAppointmentId())
                .orElseThrow(() -> new IllegalStateException("Queue entry without appointment"));
        appointment.transitionTo(entry.getStatus().toAppointmentStatus());
    }

    private Map<UUID, String> patientNames(List<QueueEntry> queue) {
        List<UUID> appointmentIds = queue.stream().map(QueueEntry::getAppointmentId).toList();
        Map<UUID, UUID> patientByAppointment = appointments.findAllById(appointmentIds).stream()
                .collect(Collectors.toMap(Appointment::getId, Appointment::getPatientId));
        Map<UUID, String> nameByPatient = patients.findAllById(patientByAppointment.values()).stream()
                .collect(Collectors.toMap(Patient::getId, Patient::getFullName));

        return patientByAppointment.entrySet().stream()
                .collect(Collectors.toMap(Map.Entry::getKey, e -> nameByPatient.get(e.getValue())));
    }

    /** "Today" is the clinic's local date, not the server's. */
    private LocalDate today(UUID clinicId) {
        ZoneId zone = clinics.findById(clinicId)
                .map(clinic -> ZoneId.of(clinic.getTimezone()))
                .orElseThrow(() -> new NotFoundException("Clinic not found"));
        return LocalDate.now(clock.withZone(zone));
    }
}
