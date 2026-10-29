package com.clinicit.clinic.api;

import com.clinicit.clinic.domain.DoctorProfile;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

/**
 * @param live      seeing patients today; self check-in only sends patients to live doctors
 * @param liveSince when they went live today, or null
 * @param room      where patients go when called, or null
 */
public record DoctorResponse(
        UUID id,
        UUID clinicId,
        String displayName,
        String specialization,
        int appointmentMinutes,
        boolean live,
        Instant liveSince,
        String room
) {

    public static DoctorResponse from(DoctorProfile doctor, LocalDate today) {
        boolean live = doctor.isOnDuty(today);
        return new DoctorResponse(doctor.getId(), doctor.getClinicId(), doctor.getDisplayName(), doctor.getSpecialization(),
                doctor.getAppointmentMinutes(), live, live ? doctor.getOnDutySince() : null, doctor.getRoom());
    }
}
