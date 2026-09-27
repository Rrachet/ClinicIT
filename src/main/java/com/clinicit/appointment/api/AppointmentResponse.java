package com.clinicit.appointment.api;

import com.clinicit.appointment.domain.Appointment;
import com.clinicit.appointment.domain.AppointmentStatus;

import java.time.LocalDateTime;
import java.util.UUID;

/** Staff-facing appointment. patientName saves screens one lookup per row. */
public record AppointmentResponse(
        UUID id,
        UUID clinicId,
        UUID patientId,
        String patientName,
        UUID doctorId,
        LocalDateTime scheduledAt,
        AppointmentStatus status,
        String reasonSummary
) {
    public static AppointmentResponse from(Appointment appointment, String patientName) {
        return new AppointmentResponse(
                appointment.getId(),
                appointment.getClinicId(),
                appointment.getPatientId(),
                patientName,
                appointment.getDoctorId(),
                appointment.getScheduledAt(),
                appointment.getStatus(),
                appointment.getReasonSummary()
        );
    }
}
