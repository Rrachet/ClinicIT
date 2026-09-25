package com.clinicit.appointment.domain;

import org.springframework.data.jpa.repository.JpaRepository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

public interface AppointmentRepository extends JpaRepository<Appointment, UUID> {

    List<Appointment> findByClinicIdAndDoctorIdAndScheduledAtBetweenOrderByScheduledAtAsc(
            UUID clinicId,
            UUID doctorId,
            LocalDateTime from,
            LocalDateTime to
    );

    List<Appointment> findByClinicIdAndScheduledAtBetweenOrderByScheduledAtAsc(
            UUID clinicId,
            LocalDateTime from,
            LocalDateTime to
    );
}
