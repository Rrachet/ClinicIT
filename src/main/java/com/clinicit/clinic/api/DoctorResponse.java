package com.clinicit.clinic.api;

import com.clinicit.clinic.domain.DoctorProfile;

import java.util.UUID;

public record DoctorResponse(UUID id, UUID clinicId, String displayName, String specialization) {

    public static DoctorResponse from(DoctorProfile doctor) {
        return new DoctorResponse(doctor.getId(), doctor.getClinicId(), doctor.getDisplayName(), doctor.getSpecialization());
    }
}
