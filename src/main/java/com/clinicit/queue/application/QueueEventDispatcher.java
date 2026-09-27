package com.clinicit.queue.application;

import com.clinicit.queue.api.QueueEventMessage;
import com.clinicit.queue.domain.QueueEventRecord;
import com.clinicit.queue.domain.QueueEventRecordRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.Limit;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;
import org.springframework.transaction.support.TransactionTemplate;

import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;

/**
 * Moves outbox events to the {@link QueueEventPublisher}.
 *
 * <ul>
 *   <li><b>Fast path</b>: right after the queue transaction commits. A rolled-back change
 *       never reaches this point, so clients are never told about a state that does not
 *       exist.</li>
 *   <li><b>Retry path</b>: a scheduled poller re-sends events still unpublished after a grace
 *       period (publisher error, or a crash between commit and send).</li>
 * </ul>
 * Delivery is therefore at-least-once; clients dedupe on {@code eventId}.
 */
@Component
public class QueueEventDispatcher {

    private static final Logger log = LoggerFactory.getLogger(QueueEventDispatcher.class);
    private static final int BATCH = 200;

    private final QueueEventRecordRepository events;
    private final QueueEventPublisher publisher;
    private final TransactionTemplate newTransaction;
    private final JdbcTemplate jdbc;
    private final Clock clock;
    private final Duration retryAfter;
    private final Duration retention;

    public QueueEventDispatcher(
            QueueEventRecordRepository events,
            QueueEventPublisher publisher,
            PlatformTransactionManager transactionManager,
            JdbcTemplate jdbc,
            Clock clock,
            @Value("${clinicit.queue.events.retry-after:PT10S}") Duration retryAfter,
            @Value("${clinicit.queue.events.retention:P7D}") Duration retention
    ) {
        this.events = events;
        this.publisher = publisher;
        this.newTransaction = new TransactionTemplate(transactionManager);
        this.newTransaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        this.jdbc = jdbc;
        this.clock = clock;
        this.retryAfter = retryAfter;
        this.retention = retention;
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void afterCommit(QueueEventRecorder.Recorded recorded) {
        deliver(recorded.message());
    }

    /** Re-sends events the fast path did not confirm. Returns how many were sent. */
    @Scheduled(
            initialDelayString = "${clinicit.queue.events.poll-interval:PT5S}",
            fixedDelayString = "${clinicit.queue.events.poll-interval:PT5S}")
    public void scheduledRetry() {
        retryPending(clock.instant());
    }

    public int retryPending(Instant now) {
        List<QueueEventRecord> pending = events.findPendingOlderThan(now.minus(retryAfter), Limit.of(BATCH));
        int sent = 0;
        for (QueueEventRecord event : pending) {
            if (deliver(QueueEventMessage.from(event))) {
                sent++;
            }
        }
        return sent;
    }

    /** Published events are only a delivery log; the queue tables hold the real history. */
    @Scheduled(
            initialDelayString = "${clinicit.auth.cleanup-initial-delay:PT1M}",
            fixedDelayString = "${clinicit.auth.cleanup-interval:PT1H}")
    public void scheduledPurge() {
        purgePublished(clock.instant());
    }

    public int purgePublished(Instant now) {
        return jdbc.update("delete from queue_events where published_at is not null and published_at < ?",
                Timestamp.from(now.minus(retention)));
    }

    private boolean deliver(QueueEventMessage message) {
        try {
            publisher.publish(message);
        } catch (RuntimeException e) {
            log.warn("Queue event {} not delivered, will retry: {}", message.eventId(), e.toString());
            return false;
        }
        newTransaction.executeWithoutResult(status -> events.markPublished(message.sequence(), clock.instant()));
        return true;
    }
}
