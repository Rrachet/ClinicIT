package com.clinicit.checkin.domain;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface VisitTypeRepository extends JpaRepository<VisitType, UUID> {

    List<VisitType> findByClinicIdOrderBySortOrderAscNameAsc(UUID clinicId);

    Optional<VisitType> findByIdAndClinicId(UUID id, UUID clinicId);

    boolean existsByClinicIdAndNameIgnoreCaseAndIdNot(UUID clinicId, String name, UUID id);

    boolean existsByClinicIdAndNameIgnoreCase(UUID clinicId, String name);
}
