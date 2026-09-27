package com.clinicit.appointment.application;

import com.clinicit.appointment.api.AppointmentResponse;
import com.clinicit.appointment.api.CreateAppointmentRequest;
import com.clinicit.appointment.domain.Appointment;
import com.clinicit.appointment.domain.AppointmentConfirmed;
import com.clinicit.appointment.domain.AppointmentRepository;
import com.clinicit.appointment.domain.AppointmentStatus;
import com.clinicit.clinic.application.ClinicTime;
import com.clinicit.clinic.domain.DoctorProfileRepository;
import com.clinicit.common.domain.BusinessRuleException;
import com.clinicit.common.domain.NotFoundException;
import com.clinicit.history.application.LifecycleHistory;
import com.clinicit.identity.domain.Actor;
import com.clinicit.patient.domain.Patient;
import com.clinicit.patient.domain.PatientRepository;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

@Service
@Transactional
public class AppointmentService {

    private final AppointmentRepository repository;
    private final PatientRepository patients;
    private final DoctorProfileRepository doctors;
    private final ClinicTime clinicTime;
    private final ApplicationEventPublisher events;
    private final LifecycleHistory history;

    public AppointmentService(
            AppointmentRepository repository,
            PatientRepository patients,
            DoctorProfileRepository doctors,
            ClinicTime clinicTime,
            ApplicationEventPublisher events,
            LifecycleHistory history
    ) {
        this.repository = repository;
        this.patients = patients;
        this.doctors = doctors;
        this.clinicTime = clinicTime;
        this.events = events;
        this.history = history;
    }

    public AppointmentResponse create(Actor actor, CreateAppointmentRequest request) {
        // The appointment is booked in the caller's clinic; patient and doctor must be from it
        // too, otherwise one clinic could book (and later queue) another clinic's patients.
        Patient patient = patients.findByIdAndClinicId(request.patientId(), actor.clinicId())
                .orElseThrow(() -> new NotFoundException("Patient not found"));
        doctors.findByIdAndClinicId(request.doctorId(), actor.clinicId())
                .orElseThrow(() -> new NotFoundException("Doctor not found"));

        Appointment appointment = new Appointment();
        appointment.setClinicId(actor.clinicId());
        appointment.setPatientId(request.patientId());
        appointment.setDoctorId(request.doctorId());
        appointment.setScheduledAt(request.scheduledAt());
        appointment.setReasonSummary(request.reasonSummary());

        Appointment saved = repository.save(appointment);
        history.booked(saved, actor, clinicTime.instant());
        return AppointmentResponse.from(saved, patient.getFullName());
    }

    @Transactional(readOnly = true)
    public AppointmentResponse get(Actor actor, UUID id) {
        Appointment appointment = repository.findByIdAndClinicId(id, actor.clinicId())
                .orElseThrow(() -> new NotFoundException("Appointment not found"));
        actor.requireAccessToDoctor(appointment.getDoctorId());
        return respond(appointment);
    }

    @Transactional(readOnly = true)
    public List<AppointmentResponse> forDate(Actor actor, UUID requestedDoctorId, LocalDate date) {
        UUID clinicId = actor.clinicId();
        // Front desk may list the whole clinic or one doctor; a doctor only ever sees their own.
        UUID doctorId = actor.isDoctor() ? actor.resolveDoctor(requestedDoctorId) : requestedDoctorId;
        var from = date.atStartOfDay();
        var to = date.plusDays(1).atStartOfDay();

        List<Appointment> found = doctorId == null
                ? repository.findForClinicInRange(clinicId, from, to)
                : repository.findForDoctorInRange(clinicId, doctorId, from, to);

        Map<UUID, String> names = patients.findAllById(found.stream().map(Appointment::getPatientId).toList())
                .stream()
                .collect(Collectors.toMap(Patient::getId, Patient::getFullName));
        return found.stream()
                .map(appointment -> AppointmentResponse.from(appointment, names.get(appointment.getPatientId())))
                .toList();
    }

    public AppointmentResponse confirm(Actor actor, UUID id) {
        AppointmentResponse confirmed = transition(actor, id, AppointmentStatus.CONFIRMED);
        events.publishEvent(new AppointmentConfirmed(confirmed.id(), confirmed.clinicId()));
        return confirmed;
    }

    public AppointmentResponse cancel(Actor actor, UUID id) {
        return transition(actor, id, AppointmentStatus.CANCELLED);
    }

    public AppointmentResponse arrive(Actor actor, UUID id) {
        return transition(actor, id, AppointmentStatus.ARRIVED);
    }

    /**
     * Records a no-show for a patient who never reached the queue: either they never
     * arrived (CONFIRMED, only once the appointment time has passed) or they checked in
     * and left before joining (ARRIVED). Queued patients go through the queue's no-show.
     */
    public AppointmentResponse markNoShow(Actor actor, UUID id) {
        Appointment appointment = lockForTransition(actor, id);

        if (appointment.getStatus() == AppointmentStatus.CONFIRMED
                && clinicTime.now(appointment.getClinicId()).isBefore(appointment.getScheduledAt())) {
            throw new BusinessRuleException("TOO_EARLY",
                    "Cannot mark a no-show before the appointment time");
        }

        return respond(apply(actor, appointment, AppointmentStatus.NO_SHOW));
    }

    private AppointmentResponse transition(Actor actor, UUID id, AppointmentStatus target) {
        return respond(apply(actor, lockForTransition(actor, id), target));
    }

    /** Changes the status and records it in the operational history, in this transaction. */
    private Appointment apply(Actor actor, Appointment appointment, AppointmentStatus target) {
        AppointmentStatus previous = appointment.getStatus();
        appointment.transitionTo(target);
        history.appointmentChanged(appointment, previous, actor, clinicTime.instant());
        return appointment;
    }

    private Appointment lockForTransition(Actor actor, UUID id) {
        Appointment appointment = repository.findByIdAndClinicIdForUpdate(id, actor.clinicId())
                .orElseThrow(() -> new NotFoundException("Appointment not found"));

        // Once queued, the queue entry owns the status. Changing the appointment here
        // (e.g. SKIPPED -> NO_SHOW) would leave the queue entry out of sync.
        if (appointment.getStatus().isQueueManaged()) {
            throw new BusinessRuleException("QUEUE_MANAGED",
                    "Appointment is in the queue; change it through its queue entry");
        }
        return appointment;
    }

    private AppointmentResponse respond(Appointment appointment) {
        String patientName = patients.findById(appointment.getPatientId()).map(Patient::getFullName).orElse(null);
        return AppointmentResponse.from(appointment, patientName);
    }
}
