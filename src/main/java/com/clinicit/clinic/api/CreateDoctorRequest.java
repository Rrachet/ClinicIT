package com.clinicit.clinic.api;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** @param room where patients go when called, e.g. "Room 3"; optional */
public record CreateDoctorRequest(
        @NotBlank @Size(max = 150) String displayName,
        @Size(max = 120) String specialization,
        @Size(max = 40) String room
) {
    public CreateDoctorRequest(String displayName, String specialization) {
        this(displayName, specialization, null);
    }
}
