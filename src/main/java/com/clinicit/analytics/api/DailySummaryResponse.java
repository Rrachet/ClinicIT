package com.clinicit.analytics.api;

import java.time.LocalDate;
import java.util.UUID;

/**
 * One clinic day (or one doctor's day). Durations are in seconds; a metric with no data
 * (e.g. no patient called yet) is null rather than 0, so "no data" never reads as "instant".
 *
 * @param patients             checked into the queue that day
 * @param scheduledAppointments appointments scheduled for that day (including walk-ins)
 * @param noShowRate           no-shows ÷ (scheduled − cancelled); null when nothing is left to attend
 * @param cancellationRate     cancelled ÷ scheduled; null when nothing was scheduled
 * @param currentQueueLength   patients waiting now (for a past day, left waiting at day end)
 */
public record DailySummaryResponse(
        LocalDate date,
        String timezone,
        UUID doctorId,
        long scheduledAppointments,
        long patients,
        long completedConsultations,
        Double averageWaitSeconds,
        Double medianWaitSeconds,
        Double averageConsultationSeconds,
        Double averageDelaySeconds,
        long cancellations,
        long noShows,
        Double cancellationRate,
        Double noShowRate,
        long currentQueueLength
) {
}
