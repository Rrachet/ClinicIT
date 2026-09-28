package com.clinicit.appointment.application;

import com.clinicit.appointment.domain.AppointmentStatus;
import com.clinicit.common.metrics.AfterCommit;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.stereotype.Component;

import java.util.EnumMap;
import java.util.Map;

/**
 * {@code clinicit.appointments{status}}: appointments that reached each status, counted after
 * commit. {@code status=BOOKED} is creation, {@code COMPLETED} completion (via the queue),
 * and CANCELLED / NO_SHOW the losses. No clinic, doctor or patient tags (see QueueMetrics).
 */
@Component
public class AppointmentMetrics {

    private final Map<AppointmentStatus, Counter> counters = new EnumMap<>(AppointmentStatus.class);

    public AppointmentMetrics(MeterRegistry registry) {
        for (AppointmentStatus status : AppointmentStatus.values()) {
            counters.put(status, Counter.builder("clinicit.appointments")
                    .tag("status", status.name())
                    .description("Appointments that reached each status")
                    .register(registry));
        }
    }

    public void reached(AppointmentStatus status) {
        Counter counter = counters.get(status);
        AfterCommit.run(counter::increment);
    }
}
