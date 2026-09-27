package com.clinicit.appointment.domain;

import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface AppointmentRepository extends JpaRepository<Appointment, UUID> {

    /** Row lock so concurrent status changes (e.g. cancel vs. arrive) cannot lose updates. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select a from Appointment a where a.id = :id")
    Optional<Appointment> findByIdForUpdate(UUID id);

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
