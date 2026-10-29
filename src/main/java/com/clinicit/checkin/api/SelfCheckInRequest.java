package com.clinicit.checkin.api;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.util.UUID;

/**
 * What a patient enters after scanning the clinic's QR code.
 *
 * @param reason in their own words, for the doctor; never used to choose the doctor
 */
public record SelfCheckInRequest(
        @NotBlank @Size(max = 150) String fullName,
        @NotBlank @Size(max = 30) String phone,
        @NotNull UUID visitTypeId,
        @Size(max = 300) String reason
) {}
