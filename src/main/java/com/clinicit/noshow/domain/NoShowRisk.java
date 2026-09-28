package com.clinicit.noshow.domain;

/**
 * An advisory flag for one booked appointment, from the patient's own attendance at this
 * clinic before the day in question. Never a certainty, never a reason to refuse or cancel
 * care (docs/NO_SHOW_RISK.md).
 *
 * @param priorAppointments the patient's earlier booked appointments with a known outcome
 *                          (they came, or never arrived), the most recent 10 at most
 * @param priorMissed       of those, how many they never arrived for
 */
public record NoShowRisk(Level level, int priorAppointments, int priorMissed) {

    public enum Level {
        /** Fewer than {@link NoShowRiskRule#MIN_HISTORY} earlier appointments: no judgement. */
        UNKNOWN,
        TYPICAL,
        ELEVATED
    }
}
