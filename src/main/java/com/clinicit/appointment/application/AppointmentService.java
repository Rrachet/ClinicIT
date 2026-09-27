package com.clinicit.appointment.application;

import com.clinicit.appointment.api.AppointmentResponse;
import com.clinicit.appointment.api.CreateAppointmentRequest;
import com.clinicit.appointment.domain.Appointment;
import com.clinicit.appointment.domain.AppointmentRepository;
import com.clinicit.appointment.domain.AppointmentStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

@Service
@Transactional
public class AppointmentService {

    private final AppointmentRepository repository;

    public AppointmentService(AppointmentRepository repository) {
        this.repository = repository;
    }

    public AppointmentResponse create(CreateAppointmentRequest request) {
        Appointment appointment = new Appointment();
        appointment.setClinicId(request.clinicId());
        appointment.setPatientId(request.patientId());
        appointment.setDoctorId(request.doctorId());
        appointment.setScheduledAt(request.scheduledAt());
        appointment.setReasonSummary(request.reasonSummary());
        appointment.setStatus(AppointmentStatus.BOOKED);

        return AppointmentResponse.from(repository.save(appointment));
    }

    @Transactional(readOnly = true)
    public AppointmentResponse get(UUID id) {
        return repository.findById(id)
                .map(AppointmentResponse::from)
                .orElseThrow(() -> new IllegalArgumentException("Appointment not found"));
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
        return transition(id, AppointmentStatus.BOOKED, AppointmentStatus.CONFIRMED);
    }

    public AppointmentResponse cancel(UUID id) {
        Appointment appointment = repository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("Appointment not found"));

        if (appointment.getStatus() == AppointmentStatus.COMPLETED) {
            throw new IllegalStateException("Completed appointments cannot be cancelled");
        }

        appointment.setStatus(AppointmentStatus.CANCELLED);
        return AppointmentResponse.from(appointment);
    }

    public AppointmentResponse arrive(UUID id) {
        return transition(id, AppointmentStatus.CONFIRMED, AppointmentStatus.ARRIVED);
    }

    private AppointmentResponse transition(UUID id, AppointmentStatus expected, AppointmentStatus target) {
        Appointment appointment = repository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("Appointment not found"));

        if (appointment.getStatus() != expected) {
            throw new IllegalStateException(
                    "Cannot transition appointment from " + appointment.getStatus() + " to " + target
            );
        }

        appointment.setStatus(target);
        return AppointmentResponse.from(appointment);
    }
}
