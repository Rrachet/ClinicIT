package com.clinicit.notification.infrastructure;

import com.clinicit.notification.application.NotificationDeliveryException;
import com.clinicit.notification.application.NotificationProvider;
import com.clinicit.notification.application.OutboundMessage;
import com.clinicit.notification.domain.NotificationChannel;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Records messages instead of sending them, so the whole flow works without a paid
 * vendor. The notification row in the database is the record (status SENT, provider
 * "development"); this class also keeps the last messages in memory and logs a
 * redacted line. Active unless another provider is configured.
 *
 * <p>Like vendors that support idempotency keys, a message whose key was already accepted is
 * not recorded again: the earlier message id is returned.
 *
 * <p>{@link #failNextSends(int)} simulates a vendor outage and {@link #crashNextSends(int, boolean)}
 * a sender that dies mid-send, for demos and tests of retries. Nothing here can reach a real
 * phone: this provider has no network access at all.
 */
@Component
@ConditionalOnProperty(name = "clinicit.notifications.provider", havingValue = "development", matchIfMissing = true)
public class DevelopmentNotificationProvider implements NotificationProvider {

    private static final Logger log = LoggerFactory.getLogger(DevelopmentNotificationProvider.class);
    private static final int KEEP = 200;

    private final Deque<OutboundMessage> delivered = new ArrayDeque<>();
    private final AtomicInteger failuresToSimulate = new AtomicInteger();
    private final AtomicInteger crashesToSimulate = new AtomicInteger();
    private volatile boolean crashAfterAccepting;
    private final java.util.Set<String> acceptedKeys = java.util.Collections.newSetFromMap(
            new java.util.LinkedHashMap<>() {
                @Override
                protected boolean removeEldestEntry(java.util.Map.Entry<String, Boolean> eldest) {
                    return size() > 10_000;
                }
            });

    /** Stands in for the sending process dying mid-send; deliberately an Error, not an exception. */
    public static final class SimulatedCrash extends Error {
        SimulatedCrash() {
            super("simulated crash during send");
        }
    }

    @PostConstruct
    void warn() {
        log.warn("Development notification provider active: patient messages are recorded, NOT sent");
    }

    @Override
    public String name() {
        return "development";
    }

    @Override
    public boolean supports(NotificationChannel channel) {
        return true;
    }

    @Override
    public String send(OutboundMessage message) {
        if (failuresToSimulate.getAndUpdate(n -> Math.max(0, n - 1)) > 0) {
            throw new NotificationDeliveryException("SIMULATED_OUTAGE", true);
        }
        boolean crash = crashesToSimulate.getAndUpdate(n -> Math.max(0, n - 1)) > 0;
        if (crash && !crashAfterAccepting) {
            throw new SimulatedCrash();
        }
        synchronized (delivered) {
            if (acceptedKeys.add(message.idempotencyKey())) {
                delivered.addLast(message);
                while (delivered.size() > KEEP) {
                    delivered.removeFirst();
                }
            }
        }
        if (crash) {
            throw new SimulatedCrash(); // accepted, but the sender died before recording it
        }
        // Recipient and body (which contains the status link) are deliberately not logged.
        log.info("[dev notification] {} recorded ({} chars)", message, message.body().length());
        return "dev-" + message.idempotencyKey();
    }

    /** Messages "sent" so far (most recent last), for local inspection and tests. */
    public List<OutboundMessage> delivered() {
        synchronized (delivered) {
            return new ArrayList<>(delivered);
        }
    }

    public void failNextSends(int count) {
        failuresToSimulate.set(count);
    }

    /** The next {@code count} sends die mid-send, before or after the message was accepted. */
    public void crashNextSends(int count, boolean afterAccepting) {
        crashAfterAccepting = afterAccepting;
        crashesToSimulate.set(count);
    }

    public void reset() {
        failuresToSimulate.set(0);
        crashesToSimulate.set(0);
        synchronized (delivered) {
            acceptedKeys.clear();
        }
        synchronized (delivered) {
            delivered.clear();
        }
    }
}
