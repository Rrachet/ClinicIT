package com.clinicit.appointment.domain;

import com.clinicit.common.domain.InvalidStateTransitionException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.util.EnumSet;

import static com.clinicit.appointment.domain.AppointmentStatus.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AppointmentStatusTest {

    @ParameterizedTest(name = "{0} -> {1}")
    @CsvSource({
            "BOOKED, CONFIRMED",
            "BOOKED, CANCELLED",
            "CONFIRMED, ARRIVED",
            "CONFIRMED, CANCELLED",
            "CONFIRMED, NO_SHOW",
            "ARRIVED, WAITING",
            "ARRIVED, NO_SHOW",
            "WAITING, CALLED",
            "WAITING, SKIPPED",
            "CALLED, IN_CONSULTATION",
            "CALLED, SKIPPED",
            "IN_CONSULTATION, COMPLETED",
            "SKIPPED, WAITING",
            "SKIPPED, NO_SHOW"
    })
    void allowedTransitions(AppointmentStatus from, AppointmentStatus to) {
        assertThat(from.canTransitionTo(to)).isTrue();
    }

    @ParameterizedTest(name = "{0} -> {1}")
    @CsvSource({
            "BOOKED, ARRIVED",
            "BOOKED, WAITING",
            "BOOKED, NO_SHOW",
            "CONFIRMED, WAITING",
            "ARRIVED, CANCELLED",
            "WAITING, IN_CONSULTATION",
            "WAITING, CANCELLED",
            "CALLED, COMPLETED",
            "IN_CONSULTATION, CANCELLED",
            "IN_CONSULTATION, SKIPPED",
            "SKIPPED, CALLED"
    })
    void forbiddenTransitions(AppointmentStatus from, AppointmentStatus to) {
        assertThat(from.canTransitionTo(to)).isFalse();
    }

    @Test
    void terminalStatesAllowNothing() {
        for (AppointmentStatus terminal : EnumSet.of(COMPLETED, CANCELLED, NO_SHOW)) {
            assertThat(terminal.isTerminal()).isTrue();
            assertThat(terminal.allowedTargets()).isEmpty();
        }
    }

    @Test
    void queueManagedStatusesAreExactlyTheQueueStates() {
        assertThat(EnumSet.allOf(AppointmentStatus.class).stream().filter(AppointmentStatus::isQueueManaged))
                .containsExactlyInAnyOrder(WAITING, CALLED, IN_CONSULTATION, SKIPPED);
    }

    @Test
    void appointmentRejectsInvalidTransition() {
        Appointment appointment = new Appointment();

        assertThatThrownBy(() -> appointment.transitionTo(ARRIVED))
                .isInstanceOf(InvalidStateTransitionException.class)
                .hasMessageContaining("BOOKED to ARRIVED");
        assertThat(appointment.getStatus()).isEqualTo(BOOKED);
    }
}
