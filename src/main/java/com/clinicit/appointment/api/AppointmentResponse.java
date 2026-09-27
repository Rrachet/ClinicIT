package com.clinicit.appointment.api;

import com.clinicit.appointment.domain.Appointment;
import com.clinicit.appointment.domain.AppointmentStatus;

import java.time.LocalDateTime;
import java.util.UUID;

public record AppointmentResponse(
        UUID id,
        UUID clinicId,
        UUID patientId,
        UUID doctorId,
        LocalDateTime scheduledAt,
        AppointmentStatus status,
        String reasonSummary
) {
    public static AppointmentResponse from(Appointment appointment) {
        return new AppointmentResponse(
                appointment.getId(),
                appointment.getClinicId(),
                appointment.getPatientId(),
                appointment.getDoctorId(),
                appointment.getScheduledAt(),
                appointment.getStatus(),
                appointment.getReasonSummary()
        );
    }
}
