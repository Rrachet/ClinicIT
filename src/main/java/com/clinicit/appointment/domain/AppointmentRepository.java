package com.clinicit.appointment.domain;

import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;

import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface AppointmentRepository extends JpaRepository<Appointment, UUID> {

    Optional<Appointment> findByIdAndClinicId(UUID id, UUID clinicId);

    List<Appointment> findByClinicIdAndIdIn(UUID clinicId, Collection<UUID> ids);

    /**
     * Row lock so concurrent status changes (e.g. cancel vs. arrive) cannot lose updates.
     * Clinic-scoped: another clinic's appointment is simply not found.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select a from Appointment a where a.id = :id and a.clinicId = :clinicId")
    Optional<Appointment> findByIdAndClinicIdForUpdate(UUID id, UUID clinicId);

    /** Appointments in [from, to): a day's listing must not include the next day's 00:00 slot. */
    @Query("""
            select a from Appointment a
            where a.clinicId = :clinicId
              and a.doctorId = :doctorId
              and a.scheduledAt >= :from and a.scheduledAt < :to
            order by a.scheduledAt
            """)
    List<Appointment> findForDoctorInRange(UUID clinicId, UUID doctorId, LocalDateTime from, LocalDateTime to);

    @Query("""
            select a from Appointment a
            where a.clinicId = :clinicId
              and a.scheduledAt >= :from and a.scheduledAt < :to
            order by a.scheduledAt
            """)
    List<Appointment> findForClinicInRange(UUID clinicId, LocalDateTime from, LocalDateTime to);
}
