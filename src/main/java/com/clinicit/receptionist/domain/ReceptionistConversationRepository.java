package com.clinicit.receptionist.domain;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

public interface ReceptionistConversationRepository extends JpaRepository<ReceptionistConversation, UUID> {

    Optional<ReceptionistConversation> findByIdAndClinicId(UUID id, UUID clinicId);

    @Modifying
    @Transactional
    @Query("delete from ReceptionistConversation c where c.updatedAt < :before")
    int deleteUpdatedBefore(Instant before);
}
