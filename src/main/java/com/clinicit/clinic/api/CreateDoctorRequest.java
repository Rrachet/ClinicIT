package com.clinicit.clinic.api;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record CreateDoctorRequest(
        @NotBlank @Size(max = 150) String displayName,
        @Size(max = 120) String specialization
) {}
