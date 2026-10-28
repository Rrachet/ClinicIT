package com.clinicit.queue.application;

import com.clinicit.queue.api.QueueEventMessage;
import com.clinicit.queue.domain.QueueEventRecord;
import com.clinicit.queue.domain.QueueEventRecordRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.data.domain.Limit;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Delivers queue events to this instance's clients when they were committed by another instance.
 *
 * <p>The {@link QueueEventDispatcher} publishes each event once, on the instance that committed
 * it, through an in-memory broker. With several instances (for example serverless containers
 * that scale out), clients connected to the other instances would never hear about it. When
 * enabled, every instance re-reads the outbox rows of the last {@code window} and passes the
 * ones it has not delivered itself to its own publisher.
 *
 * <ul>
 *   <li>A time window rather than an id cursor: outbox ids are allocated before commit, so a
 *       slow transaction can commit a lower id after a higher one; a cursor would skip it.</li>
 *   <li>Rows already in the window when the instance starts are not replayed: clients load
 *       current state over REST when they connect.</li>
 *   <li>A failed publish is retried on the next tick. Delivery stays at-least-once and clients
 *       dedupe on {@code eventId}.</li>
 * </ul>
 * Off by default: a single instance already gets every event from the dispatcher.
 */
@Component
@ConditionalOnProperty(name = "clinicit.queue.events.relay.enabled", havingValue = "true")
public class QueueEventRelay {

    private static final Logger log = LoggerFactory.getLogger(QueueEventRelay.class);
    private static final int BATCH = 500;

    private final QueueEventRecordRepository events;
    private final QueueEventPublisher publisher;
    private final Clock clock;
    private final Duration window;
    /** Outbox ids this instance has delivered (or seen at start), with when they occurred. */
    private final Map<Long, Instant> delivered = new ConcurrentHashMap<>();
    private volatile boolean primed;

    public QueueEventRelay(
            QueueEventRecordRepository events,
            QueueEventPublisher publisher,
            Clock clock,
            @Value("${clinicit.queue.events.relay.window:PT30S}") Duration window
    ) {
        this.events = events;
        this.publisher = publisher;
        this.clock = clock;
        this.window = window;
    }

    /** Events committed here were already published by the dispatcher's fast path. */
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void deliveredLocally(QueueEventRecorder.Recorded recorded) {
        delivered.put(recorded.message().sequence(), recorded.message().occurredAt());
    }

    @Scheduled(
            initialDelayString = "${clinicit.queue.events.relay.interval:PT1S}",
            fixedDelayString = "${clinicit.queue.events.relay.interval:PT1S}")
    public void scheduledRelay() {
        relay(clock.instant());
    }

    /** Publishes recent events this instance has not delivered. Returns how many were sent. */
    public int relay(Instant now) {
        var recent = events.findOccurredSince(now.minus(window), Limit.of(BATCH));
        if (!primed) {
            recent.forEach(event -> delivered.put(event.getId(), event.getOccurredAt()));
            primed = true;
            return 0;
        }
        int sent = 0;
        for (QueueEventRecord event : recent) {
            if (delivered.containsKey(event.getId())) {
                continue;
            }
            try {
                publisher.publish(QueueEventMessage.from(event));
            } catch (RuntimeException e) {
                log.warn("Queue event {} not relayed, will retry: {}", event.getEventId(), e.toString());
                continue;
            }
            delivered.put(event.getId(), event.getOccurredAt());
            sent++;
        }
        Instant forgetBefore = now.minus(window.multipliedBy(2));
        delivered.values().removeIf(occurredAt -> occurredAt.isBefore(forgetBefore));
        return sent;
    }
}
