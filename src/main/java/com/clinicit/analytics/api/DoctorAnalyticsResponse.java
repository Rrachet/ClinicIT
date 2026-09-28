package com.clinicit.analytics.api;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * Per-doctor figures for one day. A doctor sees only their own row.
 *
 * @param utilization          time in consultation ÷ the doctor's working span (first call to
 *                             last completion), 0–1: how busy they were while seeing patients.
 *                             Null when the span is empty
 * @param scheduledMinutes     minutes the doctor was scheduled to see patients that day (hours
 *                             minus break and time off); null when they have no schedule
 * @param scheduledUtilization time in consultation ÷ scheduled minutes, 0–1; null without a
 *                             schedule or scheduled minutes
 */
public record DoctorAnalyticsResponse(LocalDate date, List<Doctor> doctors) {

    public record Doctor(
            UUID doctorId,
            String doctorName,
            long patientsCalled,
            long patientsHandled,
            Double averageWaitSeconds,
            Double averageConsultationSeconds,
            Double consultationSeconds,
            Double utilization,
            Long scheduledMinutes,
            Double scheduledUtilization
    ) {
        public Doctor withSchedule(long minutes, Double utilizationOfSchedule) {
            return new Doctor(doctorId, doctorName, patientsCalled, patientsHandled, averageWaitSeconds,
                    averageConsultationSeconds, consultationSeconds, utilization, minutes, utilizationOfSchedule);
        }
    }
}
