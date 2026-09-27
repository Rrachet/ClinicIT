package com.clinicit.identity.api;

import com.clinicit.identity.domain.Role;
import com.clinicit.identity.domain.UserAccount;

import java.util.UUID;

public record UserResponse(
        UUID id,
        UUID clinicId,
        String email,
        String fullName,
        Role role,
        UUID doctorProfileId,
        boolean enabled
) {
    public static UserResponse from(UserAccount user) {
        return new UserResponse(
                user.getId(),
                user.getClinicId(),
                user.getEmail(),
                user.getFullName(),
                user.getRole(),
                user.getDoctorProfileId(),
                user.isEnabled()
        );
    }
}
