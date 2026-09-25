package com.clinicit.queue.domain;

import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface QueueEntryRepository extends JpaRepository<QueueEntry, UUID> {

    List<QueueEntry> findByClinicIdAndQueueDateOrderByTokenNumberAsc(
            UUID clinicId,
            LocalDate queueDate
    );

    Optional<QueueEntry> findByAppointmentId(UUID appointmentId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("""
            select q from QueueEntry q
            where q.clinicId = :clinicId
              and q.queueDate = :queueDate
            order by q.tokenNumber desc
            """)
    List<QueueEntry> findLatestForUpdate(UUID clinicId, LocalDate queueDate);
}
