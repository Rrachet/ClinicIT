package com.clinicit.analytics.api;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * Queue movement through one day, per clinic-local hour, up to the current hour for today.
 *
 * @param completed   consultations completed during the hour (throughput)
 * @param queueLength patients waiting at the end of the hour (or now, for the current hour)
 */
public record QueueAnalyticsResponse(LocalDate date, UUID doctorId, long currentQueueLength, List<Hour> byHour) {

    public record Hour(int hour, long joined, long completed, long queueLength) {}
}
