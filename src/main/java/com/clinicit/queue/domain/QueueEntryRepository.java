package com.clinicit.queue.domain;

import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;

import java.time.LocalDate;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface QueueEntryRepository extends JpaRepository<QueueEntry, UUID> {

    List<QueueEntry> findByDoctorIdAndQueueDateOrderByTokenNumberAsc(UUID doctorId, LocalDate queueDate);

    Optional<QueueEntry> findByAppointmentId(UUID appointmentId);

    boolean existsByDoctorIdAndQueueDateAndStatusIn(
            UUID doctorId,
            LocalDate queueDate,
            Collection<QueueStatus> statuses
    );

    Optional<QueueEntry> findByIdAndClinicId(UUID id, UUID clinicId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select q from QueueEntry q where q.id = :id and q.clinicId = :clinicId")
    Optional<QueueEntry> findByIdAndClinicIdForUpdate(UUID id, UUID clinicId);

    /**
     * Next waiting patient for a doctor, locked.
     *
     * FOR NO KEY UPDATE matches the lock Hibernate takes for PESSIMISTIC_WRITE and,
     * unlike FOR UPDATE, does not block foreign-key checks from concurrent inserts.
     *
     * SKIP LOCKED matters here: a WAITING row that is locked is being skipped by
     * another transaction. Without it, Postgres would wait, re-check the row after
     * the skip commits, find it no longer WAITING and, because of LIMIT 1, return
     * nothing even though other patients are still waiting.
     */
    @Query(value = """
            select * from queue_entries
            where doctor_id = :doctorId
              and queue_date = :queueDate
              and status = 'WAITING'
            order by token_number
            limit 1
            for no key update skip locked
            """, nativeQuery = true)
    Optional<QueueEntry> findNextWaitingForUpdate(UUID doctorId, LocalDate queueDate);
}
