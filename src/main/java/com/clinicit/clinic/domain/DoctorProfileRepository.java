package com.clinicit.clinic.domain;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface DoctorProfileRepository extends JpaRepository<DoctorProfile, UUID> {

    List<DoctorProfile> findByClinicIdOrderByDisplayNameAsc(UUID clinicId);
}
