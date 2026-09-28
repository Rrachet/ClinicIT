package com.clinicit.noshow.domain;

/**
 * The whole rule, deliberately simple enough to explain at the front desk:
 *
 * <blockquote>Elevated when the patient has at least 3 earlier appointments with a known
 * outcome (of their last 10), missed at least 2 of them, and their miss rate is at least 30%
 * and at least twice the clinic's.</blockquote>
 *
 * <p>It uses only the patient's own attendance at this clinic: no age, sex, name, phone,
 * address or anything else about who they are. It is not a trained model; whether it helps
 * is measured on the clinic's own history by the temporal evaluation.
 */
public final class NoShowRiskRule {

    public static final int MIN_HISTORY = 3;
    public static final int MIN_MISSED = 2;
    public static final double MIN_RATE = 0.30;
    public static final double CLINIC_MULTIPLE = 2.0;
    public static final int HISTORY_WINDOW = 10;

    private NoShowRiskRule() {}

    /** @param clinicMissRate the clinic's never-arrived rate before the same moment, or null if unknown */
    public static NoShowRisk assess(int priorAppointments, int priorMissed, Double clinicMissRate) {
        if (priorAppointments < MIN_HISTORY) {
            return new NoShowRisk(NoShowRisk.Level.UNKNOWN, priorAppointments, priorMissed);
        }
        double rate = (double) priorMissed / priorAppointments;
        double threshold = Math.max(MIN_RATE, clinicMissRate == null ? 0 : CLINIC_MULTIPLE * clinicMissRate);
        boolean elevated = priorMissed >= MIN_MISSED && rate >= threshold;
        return new NoShowRisk(elevated ? NoShowRisk.Level.ELEVATED : NoShowRisk.Level.TYPICAL,
                priorAppointments, priorMissed);
    }
}
