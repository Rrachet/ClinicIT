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

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select q from QueueEntry q where q.id = :id")
    Optional<QueueEntry> findByIdForUpdate(UUID id);

    /**
     * Next waiting patient for a doctor, locked.
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
            for update skip locked
            """, nativeQuery = true)
    Optional<QueueEntry> findNextWaitingForUpdate(UUID doctorId, LocalDate queueDate);
}
