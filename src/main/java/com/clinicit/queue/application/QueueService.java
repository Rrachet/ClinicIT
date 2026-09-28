package com.clinicit.queue.application;

import com.clinicit.appointment.domain.Appointment;
import com.clinicit.appointment.domain.AppointmentRepository;
import com.clinicit.appointment.domain.AppointmentStatus;
import com.clinicit.clinic.application.ClinicTime;
import com.clinicit.clinic.domain.DoctorProfile;
import com.clinicit.clinic.domain.DoctorProfileRepository;
import com.clinicit.common.domain.BusinessRuleException;
import com.clinicit.common.domain.InvalidStateTransitionException;
import com.clinicit.common.domain.NotFoundException;
import com.clinicit.history.application.LifecycleHistory;
import com.clinicit.identity.domain.Actor;
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

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.BiConsumer;
import java.util.stream.Collectors;

/**
 * Queue engine. Every lookup is scoped to the caller's clinic (another clinic's ids
 * are simply "not found"), and doctors may only act on their own queue.
 *
 * <p>Every mutation runs in one transaction that updates the queue
 * entry and its appointment together, so the two never disagree.
 *
 * <p>Every change also writes an outbox event in the same transaction
 * ({@link QueueEventRecorder}); clients are notified only after commit. It is also
 * appended to the permanent operational history ({@link LifecycleHistory}).
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
    private final ClinicTime clinicTime;
    private final QueueEventRecorder events;
    private final PatientRepository patients;
    private final LifecycleHistory history;
    private final QueueMetrics metrics;

    public QueueService(
            QueueEntryRepository entries,
            QueueTokenAllocator tokens,
            AppointmentRepository appointments,
            DoctorProfileRepository doctors,
            PatientRepository patients,
            ClinicTime clinicTime,
            QueueEventRecorder events,
            LifecycleHistory history,
            QueueMetrics metrics
    ) {
        this.entries = entries;
        this.tokens = tokens;
        this.appointments = appointments;
        this.doctors = doctors;
        this.patients = patients;
        this.clinicTime = clinicTime;
        this.events = events;
        this.history = history;
        this.metrics = metrics;
    }

    /** Puts an ARRIVED appointment into today's queue and issues its token. */
    public QueueEntryResponse join(Actor actor, UUID appointmentId) {
        Appointment appointment = appointments.findByIdAndClinicIdForUpdate(appointmentId, actor.clinicId())
                .orElseThrow(() -> new NotFoundException("Appointment not found"));

        // Fail before touching the token counter.
        if (!appointment.getStatus().canTransitionTo(AppointmentStatus.WAITING)) {
            throw new InvalidStateTransitionException("appointment", appointment.getStatus(), AppointmentStatus.WAITING);
        }

        LocalDate today = clinicTime.today(appointment.getClinicId());
        if (!appointment.getScheduledAt().toLocalDate().equals(today)) {
            throw new BusinessRuleException("NOT_TODAY", "Only today's appointments can join the queue");
        }

        int token = tokens.nextToken(appointment.getClinicId(), today);
        Instant now = clinicTime.instant();
        QueueEntry entry = entries.save(QueueEntry.join(appointment, today, token, now));
        AppointmentStatus previous = appointment.getStatus();
        appointment.transitionTo(AppointmentStatus.WAITING);
        history.queueChanged(appointment, entry, previous, actor, now);
        events.record(entry, null, now);
        metrics.joined();

        return QueueEntryResponse.from(entry);
    }

    /**
     * Calls the waiting patient with the lowest token for this doctor.
     *
     * <p>A doctor has at most one active patient (CALLED or IN_CONSULTATION), so
     * the current patient must be completed or skipped first. The doctor row lock
     * makes the check-then-call atomic across concurrent callers.
     */
    public QueueEntryResponse callNext(Actor actor, UUID requestedDoctorId) {
        UUID doctorId = actor.resolveDoctor(requestedDoctorId);
        DoctorProfile doctor = doctors.findByIdAndClinicIdForUpdate(doctorId, actor.clinicId())
                .orElseThrow(() -> new NotFoundException("Doctor not found"));
        LocalDate today = clinicTime.today(doctor.getClinicId());

        if (entries.existsByDoctorIdAndQueueDateAndStatusIn(doctorId, today, QueueStatus.ACTIVE)) {
            throw new BusinessRuleException("DOCTOR_BUSY",
                    "Doctor already has a patient called or in consultation");
        }

        QueueEntry next = entries.findNextWaitingForUpdate(doctorId, today)
                .orElseThrow(() -> new BusinessRuleException("QUEUE_EMPTY", "No patients waiting"));

        Instant now = clinicTime.instant();
        next.call(now);
        syncAppointment(actor, next, now);
        events.record(next, QueueStatus.WAITING, now);
        // Wait until the first call only; a requeued patient's later call is not a new wait.
        metrics.called(next.getSkippedAt() == null ? Duration.between(next.getCheckedInAt(), now) : null);
        return QueueEntryResponse.from(next);
    }

    public QueueEntryResponse startConsultation(Actor actor, UUID entryId) {
        return mutate(actor, entryId, QueueEntry::startConsultation);
    }

    public QueueEntryResponse complete(Actor actor, UUID entryId) {
        return mutate(actor, entryId, QueueEntry::complete);
    }

    public QueueEntryResponse skip(Actor actor, UUID entryId) {
        return mutate(actor, entryId, QueueEntry::skip);
    }

    public QueueEntryResponse requeue(Actor actor, UUID entryId) {
        return mutate(actor, entryId, QueueEntry::requeue);
    }

    public QueueEntryResponse markNoShow(Actor actor, UUID entryId) {
        return mutate(actor, entryId, QueueEntry::markNoShow);
    }

    @Transactional(readOnly = true)
    public QueueEntryResponse get(Actor actor, UUID entryId) {
        QueueEntry entry = entries.findByIdAndClinicId(entryId, actor.clinicId())
                .orElseThrow(() -> new NotFoundException("Queue entry not found"));
        actor.requireAccessToDoctor(entry.getDoctorId());
        return QueueEntryResponse.from(entry);
    }

    /** Today's queue for one doctor, in token order. For staff screens (includes patient names). */
    @Transactional(readOnly = true)
    public QueueBoardResponse todayForDoctor(Actor actor, UUID requestedDoctorId) {
        UUID doctorId = actor.resolveDoctor(requestedDoctorId);
        DoctorProfile doctor = doctors.findByIdAndClinicId(doctorId, actor.clinicId())
                .orElseThrow(() -> new NotFoundException("Doctor not found"));
        LocalDate today = clinicTime.today(doctor.getClinicId());

        List<QueueEntry> queue = entries.findByDoctorIdAndQueueDateOrderByTokenNumberAsc(doctorId, today);
        Map<UUID, String> names = patientNames(doctor.getClinicId(), queue);

        return QueueBoardResponse.from(doctorId, today, queue, names);
    }

    private QueueEntryResponse mutate(Actor actor, UUID entryId, BiConsumer<QueueEntry, Instant> transition) {
        QueueEntry entry = entries.findByIdAndClinicIdForUpdate(entryId, actor.clinicId())
                .orElseThrow(() -> new NotFoundException("Queue entry not found"));
        actor.requireAccessToDoctor(entry.getDoctorId());

        QueueStatus previous = entry.getStatus();
        Instant now = clinicTime.instant();
        transition.accept(entry, now);
        syncAppointment(actor, entry, now);
        events.record(entry, previous, now);
        metrics.transitioned(entry.getStatus(),
                entry.getStatus() == QueueStatus.COMPLETED && entry.getConsultationStartedAt() != null
                        ? Duration.between(entry.getConsultationStartedAt(), now) : null);
        return QueueEntryResponse.from(entry);
    }

    /** Mirrors the entry's new status onto its appointment and records it in the operational history. */
    private void syncAppointment(Actor actor, QueueEntry entry, Instant now) {
        Appointment appointment = appointments.findByIdAndClinicIdForUpdate(entry.getAppointmentId(), entry.getClinicId())
                .orElseThrow(() -> new IllegalStateException("Queue entry without appointment"));
        AppointmentStatus previous = appointment.getStatus();
        appointment.transitionTo(entry.getStatus().toAppointmentStatus());
        history.queueChanged(appointment, entry, previous, actor, now);
    }

    private Map<UUID, String> patientNames(UUID clinicId, List<QueueEntry> queue) {
        List<UUID> appointmentIds = queue.stream().map(QueueEntry::getAppointmentId).toList();
        Map<UUID, UUID> patientByAppointment = appointments.findByClinicIdAndIdIn(clinicId, appointmentIds).stream()
                .collect(Collectors.toMap(Appointment::getId, Appointment::getPatientId));
        Map<UUID, String> nameByPatient = patients.findByClinicIdAndIdIn(clinicId, patientByAppointment.values()).stream()
                .collect(Collectors.toMap(Patient::getId, Patient::getFullName));

        return patientByAppointment.entrySet().stream()
                .collect(Collectors.toMap(Map.Entry::getKey, e -> nameByPatient.get(e.getValue())));
    }
}
