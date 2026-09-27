package com.clinicit.analytics.api;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * No-shows and cancellations for appointments scheduled in a date range (clinic-local, inclusive).
 *
 * @param neverArrived     no-show recorded while CONFIRMED
 * @param leftBeforeQueue  no-show recorded after arriving, before joining the queue
 * @param leftQueue        skipped in the queue and never came back
 */
public record NoShowResponse(
        LocalDate from,
        LocalDate to,
        UUID doctorId,
        long scheduledAppointments,
        long cancellations,
        long noShows,
        Double cancellationRate,
        Double noShowRate,
        long neverArrived,
        long leftBeforeQueue,
        long leftQueue,
        List<Day> byDay
) {
    public record Day(LocalDate date, long scheduledAppointments, long cancellations, long noShows) {}
}
