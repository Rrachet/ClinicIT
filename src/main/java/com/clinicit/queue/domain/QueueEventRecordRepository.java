package com.clinicit.queue.domain;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;

import java.time.Instant;
import java.util.List;

public interface QueueEventRecordRepository extends JpaRepository<QueueEventRecord, Long> {

    /** Oldest-first pending events that the after-commit path should already have sent. */
    @Query("""
            select e from QueueEventRecord e
            where e.publishedAt is null and e.occurredAt <= :olderThan
            order by e.id
            """)
    List<QueueEventRecord> findPendingOlderThan(Instant olderThan, org.springframework.data.domain.Limit limit);

    @Modifying
    @Query("update QueueEventRecord e set e.publishedAt = :now where e.id = :id and e.publishedAt is null")
    int markPublished(Long id, Instant now);
}
