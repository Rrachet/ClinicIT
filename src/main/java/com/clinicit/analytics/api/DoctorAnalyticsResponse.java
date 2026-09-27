package com.clinicit.analytics.api;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * Per-doctor figures for one day. A doctor sees only their own row.
 *
 * @param utilization time in consultation ÷ the doctor's working span (first call to last
 *                    completion), 0–1. A proxy until working hours are modelled; null when
 *                    the span is empty
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
            Double utilization
    ) {}
}
