package com.clinicit.identity.api;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/** @param newPassword 12-72 characters (bcrypt only uses the first 72 bytes) */
public record ChangePasswordRequest(
        @NotNull @Size(max = 128) String currentPassword,
        @NotNull @Size(min = 12, max = 72) String newPassword
) {
    @Override
    public String toString() {
        return "ChangePasswordRequest[***]";
    }
}
