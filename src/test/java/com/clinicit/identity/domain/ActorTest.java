package com.clinicit.identity.domain;

import com.clinicit.common.domain.ForbiddenException;
import com.clinicit.common.domain.InvalidRequestException;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ActorTest {

    private final UUID clinic = UUID.randomUUID();
    private final UUID ownDoctor = UUID.randomUUID();
    private final Actor doctor = new Actor(UUID.randomUUID(), clinic, Role.DOCTOR, ownDoctor);
    private final Actor desk = new Actor(UUID.randomUUID(), clinic, Role.RECEPTIONIST, null);

    @Test
    void doctorDefaultsToThemselvesAndCannotPickAColleague() {
        assertThat(doctor.resolveDoctor(null)).isEqualTo(ownDoctor);
        assertThat(doctor.resolveDoctor(ownDoctor)).isEqualTo(ownDoctor);
        assertThatThrownBy(() -> doctor.resolveDoctor(UUID.randomUUID())).isInstanceOf(ForbiddenException.class);
    }

    @Test
    void frontDeskMustNameADoctorAndMayPickAny() {
        UUID any = UUID.randomUUID();
        assertThat(desk.resolveDoctor(any)).isEqualTo(any);
        assertThatThrownBy(() -> desk.resolveDoctor(null)).isInstanceOf(InvalidRequestException.class);
    }

    @Test
    void doctorProfileIsRequiredForDoctorsOnly() {
        assertThatThrownBy(() -> new Actor(UUID.randomUUID(), clinic, Role.DOCTOR, null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new Actor(UUID.randomUUID(), clinic, Role.ADMIN, UUID.randomUUID()))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
