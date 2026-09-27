package com.clinicit.identity.domain;

import com.clinicit.common.domain.ForbiddenException;
import com.clinicit.common.domain.InvalidRequestException;

import java.util.Objects;
import java.util.UUID;

/**
 * The authenticated staff member a request acts for. Services take this explicitly
 * instead of reading ambient security state, so every clinic-scoped query visibly
 * starts from {@link #clinicId()}.
 *
 * @param doctorProfileId set only for {@link Role#DOCTOR}
 */
public record Actor(UUID userId, UUID clinicId, Role role, UUID doctorProfileId) {

    public Actor {
        Objects.requireNonNull(userId);
        Objects.requireNonNull(clinicId);
        Objects.requireNonNull(role);
        if ((role == Role.DOCTOR) != (doctorProfileId != null)) {
            throw new IllegalArgumentException("Only doctors, and all doctors, have a doctor profile");
        }
    }

    public boolean isDoctor() {
        return role == Role.DOCTOR;
    }

    /** Doctors may only act on their own schedule and queue; front-desk roles on any doctor's. */
    public void requireAccessToDoctor(UUID doctorId) {
        if (isDoctor() && !doctorProfileId.equals(doctorId)) {
            throw new ForbiddenException("Doctors can only access their own appointments and queue");
        }
    }

    /**
     * The doctor a request is about: the one asked for, or for a doctor with no
     * explicit choice, themselves.
     */
    public UUID resolveDoctor(UUID requestedDoctorId) {
        if (requestedDoctorId == null) {
            if (!isDoctor()) {
                throw new InvalidRequestException("doctorId is required");
            }
            return doctorProfileId;
        }
        requireAccessToDoctor(requestedDoctorId);
        return requestedDoctorId;
    }
}
