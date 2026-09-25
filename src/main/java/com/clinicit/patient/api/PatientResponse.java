package com.clinicit.patient.api;

import com.clinicit.patient.domain.Patient;

import java.time.LocalDate;
import java.util.UUID;

public record PatientResponse(
        UUID id,
        UUID clinicId,
        String fullName,
        String phone,
        LocalDate dateOfBirth
) {
    public static PatientResponse from(Patient patient) {
        return new PatientResponse(
                patient.getId(),
                patient.getClinicId(),
                patient.getFullName(),
                patient.getPhone(),
                patient.getDateOfBirth()
        );
    }
}
