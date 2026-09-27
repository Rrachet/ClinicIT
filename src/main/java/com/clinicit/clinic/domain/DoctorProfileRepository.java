package com.clinicit.clinic.domain;

import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface DoctorProfileRepository extends JpaRepository<DoctorProfile, UUID> {

    List<DoctorProfile> findByClinicIdOrderByDisplayNameAsc(UUID clinicId);

    /**
     * Locks the doctor row. The queue engine takes this lock before "call next" so
     * two receptionists (or a receptionist and the doctor) calling at the same
     * moment are serialised per doctor instead of both calling a patient.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select d from DoctorProfile d where d.id = :id")
    Optional<DoctorProfile> findByIdForUpdate(UUID id);
}
