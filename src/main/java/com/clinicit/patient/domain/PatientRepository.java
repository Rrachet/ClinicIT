package com.clinicit.patient.domain;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface PatientRepository extends JpaRepository<Patient, UUID> {

    Optional<Patient> findByIdAndClinicId(UUID id, UUID clinicId);

    List<Patient> findByClinicIdAndIdIn(UUID clinicId, Collection<UUID> ids);

    List<Patient> findTop20ByClinicIdAndFullNameContainingIgnoreCaseOrderByFullNameAsc(
            UUID clinicId,
            String fullName
    );
}
