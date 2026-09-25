package com.clinicit.patient.domain;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface PatientRepository extends JpaRepository<Patient, UUID> {

    Optional<Patient> findFirstByClinicIdAndPhone(UUID clinicId, String phone);

    List<Patient> findTop20ByClinicIdAndFullNameContainingIgnoreCaseOrderByFullNameAsc(
            UUID clinicId,
            String fullName
    );
}
