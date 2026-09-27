package com.clinicit.queue.domain;

import com.clinicit.appointment.domain.Appointment;
import com.clinicit.appointment.domain.AppointmentStatus;
import com.clinicit.common.domain.InvalidStateTransitionException;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class QueueEntryTest {

    private static final Instant T0 = Instant.parse("2026-03-10T05:30:00Z");

    private QueueEntry newEntry() {
        Appointment appointment = new Appointment();
        appointment.setClinicId(UUID.randomUUID());
        appointment.setDoctorId(UUID.randomUUID());
        return QueueEntry.join(appointment, LocalDate.of(2026, 3, 10), 27, T0);
    }

    @Test
    void joinCreatesWaitingEntryCopyingAppointmentScope() {
        Appointment appointment = new Appointment();
        appointment.setClinicId(UUID.randomUUID());
        appointment.setDoctorId(UUID.randomUUID());

        QueueEntry entry = QueueEntry.join(appointment, LocalDate.of(2026, 3, 10), 27, T0);

        assertThat(entry.getStatus()).isEqualTo(QueueStatus.WAITING);
        assertThat(entry.getTokenNumber()).isEqualTo(27);
        assertThat(entry.getClinicId()).isEqualTo(appointment.getClinicId());
        assertThat(entry.getDoctorId()).isEqualTo(appointment.getDoctorId());
        assertThat(entry.getCheckedInAt()).isEqualTo(T0);
    }

    @Test
    void happyPathRecordsEachTimestamp() {
        QueueEntry entry = newEntry();

        entry.call(T0.plusSeconds(60));
        entry.startConsultation(T0.plusSeconds(120));
        entry.complete(T0.plusSeconds(600));

        assertThat(entry.getStatus()).isEqualTo(QueueStatus.COMPLETED);
        assertThat(entry.getCalledAt()).isEqualTo(T0.plusSeconds(60));
        assertThat(entry.getConsultationStartedAt()).isEqualTo(T0.plusSeconds(120));
        assertThat(entry.getCompletedAt()).isEqualTo(T0.plusSeconds(600));
        assertThat(entry.getUpdatedAt()).isEqualTo(T0.plusSeconds(600));
    }

    @Test
    void skippedPatientCanBeRequeuedKeepingToken() {
        QueueEntry entry = newEntry();
        entry.call(T0.plusSeconds(60));
        entry.skip(T0.plusSeconds(180));

        entry.requeue(T0.plusSeconds(900));

        assertThat(entry.getStatus()).isEqualTo(QueueStatus.WAITING);
        assertThat(entry.getTokenNumber()).isEqualTo(27);
        assertThat(entry.getCalledAt()).isNull();
        assertThat(entry.getSkippedAt()).isEqualTo(T0.plusSeconds(180));
    }

    @Test
    void cannotStartConsultationWithoutBeingCalled() {
        QueueEntry entry = newEntry();

        assertThatThrownBy(() -> entry.startConsultation(T0))
                .isInstanceOf(InvalidStateTransitionException.class);
        assertThat(entry.getStatus()).isEqualTo(QueueStatus.WAITING);
    }

    @Test
    void cannotSkipDuringConsultation() {
        QueueEntry entry = newEntry();
        entry.call(T0);
        entry.startConsultation(T0);

        assertThatThrownBy(() -> entry.skip(T0)).isInstanceOf(InvalidStateTransitionException.class);
    }

    @Test
    void noShowOnlyAfterSkip() {
        QueueEntry entry = newEntry();
        assertThatThrownBy(() -> entry.markNoShow(T0)).isInstanceOf(InvalidStateTransitionException.class);

        entry.skip(T0);
        entry.markNoShow(T0);

        assertThat(entry.getStatus()).isEqualTo(QueueStatus.NO_SHOW);
        assertThat(entry.getStatus().allowedTargets()).isEmpty();
    }

    @Test
    void everyQueueTransitionIsAlsoALegalAppointmentTransition() {
        // The service mirrors each queue status onto the appointment; this guarantees
        // the two state machines can never drift apart.
        for (QueueStatus from : QueueStatus.values()) {
            for (QueueStatus to : from.allowedTargets()) {
                AppointmentStatus appointmentFrom = from.toAppointmentStatus();
                assertThat(appointmentFrom.canTransitionTo(to.toAppointmentStatus()))
                        .as("%s -> %s", from, to)
                        .isTrue();
            }
        }
        assertThat(AppointmentStatus.ARRIVED.canTransitionTo(QueueStatus.WAITING.toAppointmentStatus())).isTrue();
    }
}
