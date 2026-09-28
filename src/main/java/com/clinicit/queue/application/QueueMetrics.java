package com.clinicit.queue.application;

import com.clinicit.common.metrics.AfterCommit;
import com.clinicit.queue.domain.QueueStatus;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.springframework.stereotype.Component;

import java.time.Duration;

/**
 * Queue metrics (Micrometer), recorded only after the operation commits. No tags carry
 * clinic, doctor or patient identifiers: per-clinic figures belong to the analytics module,
 * and identifiers in metric tags would leak tenant data into the monitoring system and
 * explode its cardinality.
 *
 * <ul>
 *   <li>{@code clinicit.queue.joins}: patients who joined a queue</li>
 *   <li>{@code clinicit.queue.calls}: patients called (including calls after a requeue)</li>
 *   <li>{@code clinicit.queue.transitions{to}}: start, complete, skip, requeue (to=WAITING), no-show</li>
 *   <li>{@code clinicit.queue.wait}: check-in to first call</li>
 *   <li>{@code clinicit.consultation.duration}: consultation start to completion</li>
 * </ul>
 */
@Component
public class QueueMetrics {

    private final MeterRegistry registry;
    private final Counter joins;
    private final Counter calls;
    private final Timer wait;
    private final Timer consultation;

    public QueueMetrics(MeterRegistry registry) {
        this.registry = registry;
        this.joins = Counter.builder("clinicit.queue.joins").description("Patients who joined a queue").register(registry);
        this.calls = Counter.builder("clinicit.queue.calls").description("Patients called").register(registry);
        this.wait = Timer.builder("clinicit.queue.wait").description("Check-in to first call").register(registry);
        this.consultation = Timer.builder("clinicit.consultation.duration")
                .description("Consultation start to completion").register(registry);
    }

    void joined() {
        AfterCommit.run(joins::increment);
    }

    /** @param firstCallWait time since check-in, or null when the patient is called again after a requeue */
    void called(Duration firstCallWait) {
        AfterCommit.run(() -> {
            calls.increment();
            if (firstCallWait != null) wait.record(firstCallWait);
        });
    }

    void transitioned(QueueStatus to, Duration consultationTime) {
        AfterCommit.run(() -> {
            Counter.builder("clinicit.queue.transitions").tag("to", to.name())
                    .description("Queue entry transitions after the call").register(registry).increment();
            if (consultationTime != null) consultation.record(consultationTime);
        });
    }
}
