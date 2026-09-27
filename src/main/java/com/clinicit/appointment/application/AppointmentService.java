package com.clinicit.appointment.application;

import com.clinicit.appointment.api.AppointmentResponse;
import com.clinicit.appointment.api.CreateAppointmentRequest;
import com.clinicit.appointment.domain.Appointment;
import com.clinicit.appointment.domain.AppointmentRepository;
import com.clinicit.appointment.domain.AppointmentStatus;
import com.clinicit.clinic.application.ClinicTime;
import com.clinicit.clinic.domain.DoctorProfileRepository;
import com.clinicit.common.domain.BusinessRuleException;
import com.clinicit.common.domain.NotFoundException;
import com.clinicit.patient.domain.PatientRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

@Service
@Transactional
public class AppointmentService {

    private final AppointmentRepository repository;
    private final PatientRepository patients;
    private final DoctorProfileRepository doctors;
    private final ClinicTime clinicTime;

    public AppointmentService(
            AppointmentRepository repository,
            PatientRepository patients,
            DoctorProfileRepository doctors,
            ClinicTime clinicTime
    ) {
        this.repository = repository;
        this.patients = patients;
        this.doctors = doctors;
        this.clinicTime = clinicTime;
    }

    public AppointmentResponse create(CreateAppointmentRequest request) {
        // Patient and doctor must belong to the clinic the appointment is booked in;
        // otherwise one clinic could book (and later queue) another clinic's patients.
        patients.findById(request.patientId())
                .filter(patient -> patient.getClinicId().equals(request.clinicId()))
                .orElseThrow(() -> new NotFoundException("Patient not found in clinic"));
        doctors.findById(request.doctorId())
                .filter(doctor -> doctor.getClinicId().equals(request.clinicId()))
                .orElseThrow(() -> new NotFoundException("Doctor not found in clinic"));

        Appointment appointment = new Appointment();
        appointment.setClinicId(request.clinicId());
        appointment.setPatientId(request.patientId());
        appointment.setDoctorId(request.doctorId());
        appointment.setScheduledAt(request.scheduledAt());
        appointment.setReasonSummary(request.reasonSummary());

        return AppointmentResponse.from(repository.save(appointment));
    }

    @Transactional(readOnly = true)
    public AppointmentResponse get(UUID id) {
        return repository.findById(id)
                .map(AppointmentResponse::from)
                .orElseThrow(() -> new NotFoundException("Appointment not found"));
    }

    @Transactional(readOnly = true)
    public List<AppointmentResponse> forDate(UUID clinicId, UUID doctorId, LocalDate date) {
        var from = date.atStartOfDay();
        var to = date.plusDays(1).atStartOfDay();

        return (doctorId == null
                ? repository.findForClinicInRange(clinicId, from, to)
                : repository.findForDoctorInRange(clinicId, doctorId, from, to))
                .stream()
                .map(AppointmentResponse::from)
                .toList();
    }

    public AppointmentResponse confirm(UUID id) {
        return transition(id, AppointmentStatus.CONFIRMED);
    }

    public AppointmentResponse cancel(UUID id) {
        return transition(id, AppointmentStatus.CANCELLED);
    }

    public AppointmentResponse arrive(UUID id) {
        return transition(id, AppointmentStatus.ARRIVED);
    }

    /**
     * Records a no-show for a patient who never reached the queue: either they never
     * arrived (CONFIRMED, only once the appointment time has passed) or they checked in
     * and left before joining (ARRIVED). Queued patients go through the queue's no-show.
     */
    public AppointmentResponse markNoShow(UUID id) {
        Appointment appointment = lockForTransition(id);

        if (appointment.getStatus() == AppointmentStatus.CONFIRMED
                && clinicTime.now(appointment.getClinicId()).isBefore(appointment.getScheduledAt())) {
            throw new BusinessRuleException("TOO_EARLY",
                    "Cannot mark a no-show before the appointment time");
        }

        appointment.transitionTo(AppointmentStatus.NO_SHOW);
        return AppointmentResponse.from(appointment);
    }

    private AppointmentResponse transition(UUID id, AppointmentStatus target) {
        Appointment appointment = lockForTransition(id);
        appointment.transitionTo(target);
        return AppointmentResponse.from(appointment);
    }

    private Appointment lockForTransition(UUID id) {
        Appointment appointment = repository.findByIdForUpdate(id)
                .orElseThrow(() -> new NotFoundException("Appointment not found"));

        // Once queued, the queue entry owns the status. Changing the appointment here
        // (e.g. SKIPPED -> NO_SHOW) would leave the queue entry out of sync.
        if (appointment.getStatus().isQueueManaged()) {
            throw new BusinessRuleException("QUEUE_MANAGED",
                    "Appointment is in the queue; change it through its queue entry");
        }
        return appointment;
    }
}
