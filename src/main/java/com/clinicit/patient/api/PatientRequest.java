package com.clinicit.patient.api;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import java.time.LocalDate;

/** The patient is registered in the caller's clinic; clients cannot choose another one. */
public record PatientRequest(
        @NotBlank @Size(max = 150) String fullName,
        @NotBlank @Size(max = 30) String phone,
        LocalDate dateOfBirth
) {}
