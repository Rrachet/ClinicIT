package com.clinicit.queue.application;

import com.clinicit.queue.api.QueueEventMessage;
import com.clinicit.queue.domain.QueueEntry;
import com.clinicit.queue.domain.QueueEntryRepository;
import com.clinicit.queue.domain.QueueEventRecord;
import com.clinicit.queue.domain.QueueEventRecordRepository;
import com.clinicit.queue.domain.QueueStatus;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;

/**
 * Writes the outbox row for a queue change inside the caller's transaction, then raises
 * an application event that {@link QueueEventDispatcher} acts on only after commit.
 * The single place QueueService talks to for notifications.
 */
@Component
public class QueueEventRecorder {

    /** Raised inside the transaction; delivered to listeners only if it commits. */
    public record Recorded(QueueEventMessage message) {}

    private final QueueEntryRepository entries;
    private final QueueEventRecordRepository events;
    private final ApplicationEventPublisher applicationEvents;

    public QueueEventRecorder(
            QueueEntryRepository entries,
            QueueEventRecordRepository events,
            ApplicationEventPublisher applicationEvents
    ) {
        this.entries = entries;
        this.events = events;
        this.applicationEvents = applicationEvents;
    }

    /**
     * @param previousStatus status before the change, or null if the entry was just created
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public void record(QueueEntry entry, QueueStatus previousStatus, Instant occurredAt) {
        // Flush so the entry has its id and post-change @Version before we snapshot it.
        entries.flush();
        QueueEventRecord event = events.save(QueueEventRecord.of(entry, previousStatus, occurredAt));
        applicationEvents.publishEvent(new Recorded(QueueEventMessage.from(event)));
    }
}
