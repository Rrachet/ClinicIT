package com.clinicit.identity.api;

import com.clinicit.identity.domain.Role;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.util.UUID;

/**
 * The account is always created in the admin's own clinic; there is no clinicId field.
 *
 * @param password initial password, 12-72 characters (bcrypt only uses the first 72 bytes)
 */
public record CreateUserRequest(
        @NotBlank @Email @Size(max = 254) String email,
        @NotBlank @Size(max = 150) String fullName,
        @NotNull @Size(min = 12, max = 72) String password,
        @NotNull Role role,
        UUID doctorProfileId
) {
    @Override
    public String toString() {
        return "CreateUserRequest[email=" + email + ", role=" + role + ", password=***]";
    }
}
