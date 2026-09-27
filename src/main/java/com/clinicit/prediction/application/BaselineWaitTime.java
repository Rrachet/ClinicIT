package com.clinicit.prediction.application;

import com.clinicit.prediction.domain.WaitTimeEstimate;
import com.clinicit.prediction.domain.WaitTimeFeatures;

/**
 * The deterministic baseline: patients ahead × average consultation length, where the
 * average is today's recent consultations if there are any, else the doctor's 28-day
 * history, else {@link #DEFAULT_CONSULTATION_MINUTES}.
 *
 * <p>Used as the fallback whenever the ML service is off, slow, failing or unsure, and as the
 * yardstick the model must beat (the Python pipeline implements the same rule; both are
 * checked against {@code ml/contracts/baseline_cases.json}).
 *
 * <p>The range is a fixed rule, not a statistical interval: half the estimate to one and a
 * half times it plus five minutes. It is deliberately wide, because the baseline has no
 * measured confidence.
 */
public final class BaselineWaitTime {

    public static final String VERSION = "baseline-v1";
    public static final double DEFAULT_CONSULTATION_MINUTES = 10.0;

    private BaselineWaitTime() {}

    public static double averageConsultationMinutes(WaitTimeFeatures features) {
        if (features.recentConsultationMinutes() != null) return features.recentConsultationMinutes();
        if (features.historicalConsultationMinutes() != null) return features.historicalConsultationMinutes();
        return DEFAULT_CONSULTATION_MINUTES;
    }

    public static double minutes(WaitTimeFeatures features) {
        return features.patientsAhead() * averageConsultationMinutes(features);
    }

    public static WaitTimeEstimate estimate(WaitTimeFeatures features, String reason) {
        double minutes = minutes(features);
        int estimate = (int) Math.round(minutes);
        int lower = (int) Math.floor(minutes * 0.5);
        int upper = (int) Math.ceil(minutes * 1.5 + 5);
        return new WaitTimeEstimate(estimate, Math.min(lower, estimate), Math.max(upper, estimate),
                WaitTimeEstimate.Source.BASELINE, VERSION, reason);
    }
}
