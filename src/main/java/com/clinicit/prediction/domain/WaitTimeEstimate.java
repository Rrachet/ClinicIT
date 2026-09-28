package com.clinicit.prediction.domain;

/**
 * An approximate wait, always a range. Advisory only: it never changes the order in which
 * patients are called.
 *
 * @param source       MODEL (the ML service) or BASELINE (the deterministic rule)
 * @param modelVersion which model or rule produced it, e.g. {@code wait-hgb-…} or {@code baseline-v1}
 * @param reason       why the baseline was used (null for MODEL): ML_DISABLED, ML_UNAVAILABLE,
 *                     INVALID_PREDICTION, or a reason given by the ML service such as INSUFFICIENT_HISTORY
 */
public record WaitTimeEstimate(
        int estimatedWaitMinutes,
        int lowerBoundMinutes,
        int upperBoundMinutes,
        Source source,
        String modelVersion,
        String reason
) {
    public enum Source { MODEL, BASELINE }

    public WaitTimeEstimate {
        if (lowerBoundMinutes < 0 || lowerBoundMinutes > estimatedWaitMinutes || estimatedWaitMinutes > upperBoundMinutes) {
            throw new IllegalArgumentException("Estimate must satisfy 0 <= lower <= estimate <= upper");
        }
    }

    /**
     * The same estimate with the doctor's pauses added (see DoctorSchedule#pauseMinutesAhead).
     * Each bound gets the pause that lies within its own horizon, so the order is kept.
     */
    public WaitTimeEstimate withPauses(java.util.function.LongUnaryOperator pauseAhead) {
        int lower = lowerBoundMinutes + (int) pauseAhead.applyAsLong(lowerBoundMinutes);
        int estimate = Math.max(lower, estimatedWaitMinutes + (int) pauseAhead.applyAsLong(estimatedWaitMinutes));
        int upper = Math.max(estimate, upperBoundMinutes + (int) pauseAhead.applyAsLong(upperBoundMinutes));
        return new WaitTimeEstimate(estimate, lower, upper, source, modelVersion, reason);
    }
}
