package com.clinicit.analytics.api;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * Day-by-day figures over a range, and each doctor's workload over it. Same definitions as
 * the one-day endpoints (docs/ANALYTICS.md): every day in the range is present, with zeros
 * (and null rates and times) when nothing happened.
 */
public record TrendsResponse(LocalDate from, LocalDate to, UUID doctorId, List<Day> days, List<DoctorLoad> doctors) {

    /**
     * @param scheduled      appointments scheduled that day (walk-ins included)
     * @param checkedIn      patients who joined a queue that day
     * @param completionRate completed ÷ appointments still expected (scheduled − cancelled)
     * @param noShowRate     no-shows ÷ appointments still expected
     */
    public record Day(
            LocalDate date,
            long scheduled,
            long cancelled,
            long noShows,
            long checkedIn,
            long completed,
            Double completionRate,
            Double noShowRate,
            Double medianWaitSeconds,
            Double averageConsultationSeconds
    ) {}

    /** @param daysWorked days on which the doctor called at least one patient */
    public record DoctorLoad(
            UUID doctorId,
            String doctorName,
            long completed,
            Double consultationSeconds,
            long daysWorked,
            Double completedPerDayWorked
    ) {}
}
