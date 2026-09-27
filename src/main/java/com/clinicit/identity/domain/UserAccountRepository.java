package com.clinicit.identity.domain;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface UserAccountRepository extends JpaRepository<UserAccount, UUID> {

    /** Cross-clinic by necessity: login identifies the clinic from the account. */
    Optional<UserAccount> findByEmail(String normalizedEmail);

    boolean existsByEmail(String normalizedEmail);

    boolean existsByDoctorProfileId(UUID doctorProfileId);

    Optional<UserAccount> findByIdAndClinicId(UUID id, UUID clinicId);

    List<UserAccount> findByClinicIdOrderByFullNameAsc(UUID clinicId);
}
