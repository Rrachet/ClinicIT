package com.clinicit.appointment.application;

import com.clinicit.appointment.api.AppointmentResponse;
import com.clinicit.appointment.api.CreateAppointmentRequest;
import com.clinicit.appointment.domain.Appointment;
import com.clinicit.appointment.domain.AppointmentRepository;
import com.clinicit.appointment.domain.AppointmentStatus;
import com.clinicit.clinic.domain.DoctorProfileRepository;
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

    public AppointmentService(
            AppointmentRepository repository,
            PatientRepository patients,
            DoctorProfileRepository doctors
    ) {
        this.repository = repository;
        this.patients = patients;
        this.doctors = doctors;
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
                ? repository.findByClinicIdAndScheduledAtBetweenOrderByScheduledAtAsc(clinicId, from, to)
                : repository.findByClinicIdAndDoctorIdAndScheduledAtBetweenOrderByScheduledAtAsc(clinicId, doctorId, from, to))
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

    /** Patient checked in but left before being queued. Queued patients go through the queue's no-show. */
    public AppointmentResponse markNoShow(UUID id) {
        return transition(id, AppointmentStatus.NO_SHOW);
    }

    private AppointmentResponse transition(UUID id, AppointmentStatus target) {
        Appointment appointment = repository.findByIdForUpdate(id)
                .orElseThrow(() -> new NotFoundException("Appointment not found"));

        appointment.transitionTo(target);
        return AppointmentResponse.from(appointment);
    }
}
