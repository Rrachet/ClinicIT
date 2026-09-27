package com.clinicit.analytics.api;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * Wait = first call − joining the queue, per patient called that day. {@code byHour} groups
 * patients by the clinic-local hour they were called (the wait-time trend).
 */
public record WaitTimesResponse(
        LocalDate date,
        UUID doctorId,
        long calledPatients,
        Double averageWaitSeconds,
        Double medianWaitSeconds,
        Double p90WaitSeconds,
        Double maxWaitSeconds,
        List<Hour> byHour
) {
    public record Hour(int hour, long calledPatients, Double averageWaitSeconds, Double medianWaitSeconds) {}
}
