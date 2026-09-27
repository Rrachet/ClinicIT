package com.clinicit.queue.application;

import com.clinicit.appointment.domain.Appointment;
import com.clinicit.appointment.domain.AppointmentStatus;
import com.clinicit.clinic.domain.Clinic;
import com.clinicit.clinic.domain.DoctorProfile;
import com.clinicit.common.domain.BusinessRuleException;
import com.clinicit.common.domain.InvalidStateTransitionException;
import com.clinicit.common.domain.NotFoundException;
import com.clinicit.identity.domain.Actor;
import com.clinicit.queue.api.QueueBoardResponse;
import com.clinicit.queue.api.QueueEntryResponse;
import com.clinicit.queue.domain.QueueStatus;
import com.clinicit.support.PostgresIntegrationTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class QueueServiceIntegrationTest extends PostgresIntegrationTest {

    @Autowired QueueService queue;

    Clinic clinic;
    DoctorProfile sharma;
    DoctorProfile mehta;
    Actor desk;

    @BeforeEach
    void setUp() {
        clinic = clinic("City Clinic");
        sharma = doctor(clinic, "Dr. Sharma");
        mehta = doctor(clinic, "Dr. Mehta");
        desk = frontDesk(clinic);
    }

    private QueueEntryResponse join(DoctorProfile doctor, String patientName) {
        return queue.join(desk, arrivedAppointment(doctor, patient(clinic, patientName)).getId());
    }

    @Test
    void tokensAreSequentialPerClinicAcrossDoctors() {
        QueueEntryResponse a = join(sharma, "Rahul Kumar");
        QueueEntryResponse b = join(mehta, "Asha Rao");
        QueueEntryResponse c = join(sharma, "Vikram Singh");

        assertThat(a.tokenNumber()).isEqualTo(1);
        assertThat(b.tokenNumber()).isEqualTo(2);
        assertThat(c.tokenNumber()).isEqualTo(3);
        assertThat(a.queueDate()).isEqualTo(TODAY);
        assertThat(a.status()).isEqualTo(QueueStatus.WAITING);
        assertThat(a.doctorId()).isEqualTo(sharma.getId());
        assertThat(appointmentStatus(a.appointmentId())).isEqualTo(AppointmentStatus.WAITING);
    }

    @Test
    void tokensRestartForEachClinicAndEachDay() {
        join(sharma, "Rahul Kumar");
        join(sharma, "Asha Rao");

        Clinic other = clinic("Other Clinic");
        DoctorProfile otherDoctor = doctor(other, "Dr. Iyer");
        QueueEntryResponse otherClinicFirst =
                queue.join(frontDesk(other), arrivedAppointment(otherDoctor, patient(other, "Meera")).getId());
        assertThat(otherClinicFirst.tokenNumber()).isEqualTo(1);

        clock.set(NOW.plusSeconds(86_400));
        Appointment tomorrow = appointmentOn(sharma, patient(clinic, "Tomorrow Patient"), TODAY.plusDays(1),
                AppointmentStatus.CONFIRMED, AppointmentStatus.ARRIVED);
        QueueEntryResponse nextDayFirst = queue.join(desk, tomorrow.getId());
        assertThat(nextDayFirst.tokenNumber()).isEqualTo(1);
        assertThat(nextDayFirst.queueDate()).isEqualTo(TODAY.plusDays(1));
    }

    @Test
    void todayFollowsTheClinicTimezoneNotUtc() {
        // 20:00 UTC on 10 March is already 01:30 on 11 March in India.
        clock.set(Instant.parse("2026-03-10T20:00:00Z"));
        Appointment eleventh = appointmentOn(sharma, patient(clinic, "Late Patient"), TODAY.plusDays(1),
                AppointmentStatus.CONFIRMED, AppointmentStatus.ARRIVED);

        assertThat(queue.join(desk, eleventh.getId()).queueDate()).isEqualTo(TODAY.plusDays(1));
    }

    @Test
    void onlyArrivedAppointmentsCanJoin() {
        Appointment confirmed = appointmentOn(sharma, patient(clinic, "Not Here Yet"), TODAY,
                AppointmentStatus.CONFIRMED);

        assertThatThrownBy(() -> queue.join(desk, confirmed.getId()))
                .isInstanceOf(InvalidStateTransitionException.class);
        assertThat(appointmentStatus(confirmed.getId())).isEqualTo(AppointmentStatus.CONFIRMED);
    }

    @Test
    void appointmentCannotJoinTwice() {
        QueueEntryResponse first = join(sharma, "Rahul Kumar");

        assertThatThrownBy(() -> queue.join(desk, first.appointmentId()))
                .isInstanceOf(InvalidStateTransitionException.class);
    }

    @Test
    void onlyTodaysAppointmentsCanJoin() {
        Appointment tomorrow = appointmentOn(sharma, patient(clinic, "Early Bird"), TODAY.plusDays(1),
                AppointmentStatus.CONFIRMED, AppointmentStatus.ARRIVED);

        assertThatThrownBy(() -> queue.join(desk, tomorrow.getId()))
                .isInstanceOf(BusinessRuleException.class)
                .extracting("code").isEqualTo("NOT_TODAY");
    }

    @Test
    void failedJoinDoesNotConsumeAToken() {
        Appointment tomorrow = appointmentOn(sharma, patient(clinic, "Early Bird"), TODAY.plusDays(1),
                AppointmentStatus.CONFIRMED, AppointmentStatus.ARRIVED);
        assertThatThrownBy(() -> queue.join(desk, tomorrow.getId())).isInstanceOf(BusinessRuleException.class);

        assertThat(join(sharma, "Rahul Kumar").tokenNumber()).isEqualTo(1);
    }

    @Test
    void callNextIsFifoPerDoctor() {
        QueueEntryResponse s1 = join(sharma, "A");
        join(mehta, "B");
        QueueEntryResponse s2 = join(sharma, "C");

        QueueEntryResponse called = queue.callNext(desk, sharma.getId());
        assertThat(called.id()).isEqualTo(s1.id());
        assertThat(called.status()).isEqualTo(QueueStatus.CALLED);
        assertThat(called.calledAt()).isEqualTo(NOW);
        assertThat(appointmentStatus(s1.appointmentId())).isEqualTo(AppointmentStatus.CALLED);

        queue.startConsultation(desk, s1.id());
        queue.complete(desk, s1.id());

        assertThat(queue.callNext(desk, sharma.getId()).id()).isEqualTo(s2.id());
    }

    @Test
    void fullLifecycleKeepsAppointmentInSync() {
        QueueEntryResponse entry = join(sharma, "Rahul Kumar");

        queue.callNext(desk, sharma.getId());
        QueueEntryResponse started = queue.startConsultation(desk, entry.id());
        assertThat(started.status()).isEqualTo(QueueStatus.IN_CONSULTATION);
        assertThat(appointmentStatus(entry.appointmentId())).isEqualTo(AppointmentStatus.IN_CONSULTATION);

        QueueEntryResponse completed = queue.complete(desk, entry.id());
        assertThat(completed.status()).isEqualTo(QueueStatus.COMPLETED);
        assertThat(completed.completedAt()).isNotNull();
        assertThat(appointmentStatus(entry.appointmentId())).isEqualTo(AppointmentStatus.COMPLETED);
    }

    @Test
    void cannotCallNextWhileDoctorHasActivePatient() {
        join(sharma, "A");
        join(sharma, "B");
        queue.callNext(desk, sharma.getId());

        assertThatThrownBy(() -> queue.callNext(desk, sharma.getId()))
                .isInstanceOf(BusinessRuleException.class)
                .extracting("code").isEqualTo("DOCTOR_BUSY");
    }

    @Test
    void anotherDoctorIsNotBlocked() {
        join(sharma, "A");
        join(mehta, "B");
        queue.callNext(desk, sharma.getId());

        assertThat(queue.callNext(desk, mehta.getId()).status()).isEqualTo(QueueStatus.CALLED);
    }

    @Test
    void callNextOnEmptyQueue() {
        assertThatThrownBy(() -> queue.callNext(desk, sharma.getId()))
                .isInstanceOf(BusinessRuleException.class)
                .extracting("code").isEqualTo("QUEUE_EMPTY");
    }

    @Test
    void skippedPatientIsPassedOverThenCanReturnWithOriginalToken() {
        QueueEntryResponse first = join(sharma, "Stepped Out");
        QueueEntryResponse second = join(sharma, "Present");

        queue.callNext(desk, sharma.getId());
        QueueEntryResponse skipped = queue.skip(desk, first.id());
        assertThat(skipped.status()).isEqualTo(QueueStatus.SKIPPED);
        assertThat(appointmentStatus(first.appointmentId())).isEqualTo(AppointmentStatus.SKIPPED);

        assertThat(queue.callNext(desk, sharma.getId()).id()).isEqualTo(second.id());
        queue.startConsultation(desk, second.id());
        queue.complete(desk, second.id());

        QueueEntryResponse back = queue.requeue(desk, first.id());
        assertThat(back.status()).isEqualTo(QueueStatus.WAITING);
        assertThat(back.tokenNumber()).isEqualTo(first.tokenNumber());
        assertThat(queue.callNext(desk, sharma.getId()).id()).isEqualTo(first.id());
    }

    @Test
    void waitingPatientCanBeSkippedWithoutBeingCalled() {
        QueueEntryResponse first = join(sharma, "Went To Pharmacy");
        QueueEntryResponse second = join(sharma, "Present");

        queue.skip(desk, first.id());

        assertThat(queue.callNext(desk, sharma.getId()).id()).isEqualTo(second.id());
    }

    @Test
    void skippedPatientCanBeMarkedNoShow() {
        QueueEntryResponse entry = join(sharma, "Never Returned");
        queue.skip(desk, entry.id());

        QueueEntryResponse noShow = queue.markNoShow(desk, entry.id());

        assertThat(noShow.status()).isEqualTo(QueueStatus.NO_SHOW);
        assertThat(appointmentStatus(entry.appointmentId())).isEqualTo(AppointmentStatus.NO_SHOW);
    }

    @Test
    void invalidTransitionLeavesEntryAndAppointmentUntouched() {
        QueueEntryResponse entry = join(sharma, "Rahul Kumar");

        assertThatThrownBy(() -> queue.complete(desk, entry.id()))
                .isInstanceOf(InvalidStateTransitionException.class);

        assertThat(queue.get(desk, entry.id()).status()).isEqualTo(QueueStatus.WAITING);
        assertThat(appointmentStatus(entry.appointmentId())).isEqualTo(AppointmentStatus.WAITING);
    }

    @Test
    void boardShowsCurrentTokenAndPatientsAhead() {
        QueueEntryResponse a = join(sharma, "A");
        QueueEntryResponse b = join(sharma, "B");
        join(mehta, "Other Doctor");
        QueueEntryResponse c = join(sharma, "C");
        QueueEntryResponse d = join(sharma, "D");

        queue.callNext(desk, sharma.getId());   // A called
        queue.skip(desk, b.id());               // B stepped out

        QueueBoardResponse board = queue.todayForDoctor(desk, sharma.getId());

        assertThat(board.queueDate()).isEqualTo(TODAY);
        assertThat(board.currentToken()).isEqualTo(a.tokenNumber());
        assertThat(board.waitingCount()).isEqualTo(2);
        assertThat(board.entries()).extracting(QueueBoardResponse.Entry::tokenNumber)
                .containsExactly(1, 2, 4, 5);
        assertThat(board.entries()).extracting(QueueBoardResponse.Entry::patientName)
                .containsExactly("A", "B", "C", "D");
        assertThat(board.entries()).extracting(QueueBoardResponse.Entry::patientsAhead)
                .containsExactly(null, null, 0, 1);
        assertThat(board.entries().get(2).id()).isEqualTo(c.id());
        assertThat(board.entries().get(3).id()).isEqualTo(d.id());
    }

    @Test
    void unknownIdsAreNotFound() {
        assertThatThrownBy(() -> queue.join(desk, UUID.randomUUID())).isInstanceOf(NotFoundException.class);
        assertThatThrownBy(() -> queue.callNext(desk, UUID.randomUUID())).isInstanceOf(NotFoundException.class);
        assertThatThrownBy(() -> queue.skip(desk, UUID.randomUUID())).isInstanceOf(NotFoundException.class);
    }
}
