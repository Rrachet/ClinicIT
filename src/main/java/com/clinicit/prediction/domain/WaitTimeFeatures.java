package com.clinicit.prediction.domain;

/**
 * What ClinicIT knows about a waiting patient's situation at one moment ("as of"), and
 * nothing more. Built by {@code WaitTimeFeatureBuilder} from the operational history with
 * every event after the moment excluded, the same way for live predictions and for the
 * training dataset. Operational facts only: no name, age, sex, reason for visit or any
 * other patient attribute (docs/AI.md).
 *
 * @param dayOfWeek                     clinic-local ISO day of week, 1 (Mon) – 7 (Sun)
 * @param minuteOfDay                   clinic-local time of day in minutes since midnight
 * @param patientsAhead                 waiting patients of the same doctor with a lower token
 * @param queueLength                   waiting patients of the same doctor, this patient included
 * @param completedToday                consultations this doctor has completed today so far
 * @param doctorBusy                    1 if the doctor has a patient called or in consultation
 * @param activePatientMinutes          minutes since that patient was called (0 if none)
 * @param callsLastHour                 patients this doctor called in the past 60 minutes
 * @param recentConsultationMinutes     mean of the doctor's last ≤3 consultations completed today; null if none
 * @param historicalConsultationMinutes mean consultation length over the previous 28 days; null if none
 * @param historicalConsultationCount   how many consultations that mean is based on
 * @param historicalWaitMinutes         mean wait (join → first call) for this doctor over the previous
 *                                      28 days, for patients who joined within ±1 hour of this time of day; null if none
 */
public record WaitTimeFeatures(
        int dayOfWeek,
        double minuteOfDay,
        int patientsAhead,
        int queueLength,
        int completedToday,
        int doctorBusy,
        double activePatientMinutes,
        int callsLastHour,
        Double recentConsultationMinutes,
        Double historicalConsultationMinutes,
        int historicalConsultationCount,
        Double historicalWaitMinutes
) {
}
