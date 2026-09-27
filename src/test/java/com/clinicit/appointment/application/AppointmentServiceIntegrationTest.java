package com.clinicit.appointment.application;

import com.clinicit.appointment.domain.Appointment;
import com.clinicit.appointment.domain.AppointmentStatus;
import com.clinicit.clinic.domain.Clinic;
import com.clinicit.clinic.domain.DoctorProfile;
import com.clinicit.common.domain.BusinessRuleException;
import com.clinicit.common.domain.InvalidStateTransitionException;
import com.clinicit.queue.application.QueueService;
import com.clinicit.queue.domain.QueueStatus;
import com.clinicit.support.PostgresIntegrationTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.time.LocalDateTime;
import java.time.LocalTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AppointmentServiceIntegrationTest extends PostgresIntegrationTest {

    @Autowired AppointmentService service;
    @Autowired QueueService queue;

    Clinic clinic;
    DoctorProfile doctor;

    @BeforeEach
    void setUp() {
        clinic = clinic("City Clinic");
        doctor = doctor(clinic, "Dr. Sharma");
    }

    private Appointment confirmedAt(LocalTime clinicLocalTime) {
        Appointment appointment = appointmentOn(doctor, patient(clinic, "Rahul"), TODAY, AppointmentStatus.CONFIRMED);
        appointment.setScheduledAt(LocalDateTime.of(TODAY, clinicLocalTime));
        return appointments.save(appointment);
    }

    @Test
    void confirmedPatientWhoNeverArrivedIsRecordedAsNoShow() {
        // Clinic-local time is 11:00; the 10:30 appointment has passed.
        Appointment missed = confirmedAt(LocalTime.of(10, 30));

        assertThat(service.markNoShow(missed.getId()).status()).isEqualTo(AppointmentStatus.NO_SHOW);
        assertThat(appointmentStatus(missed.getId())).isEqualTo(AppointmentStatus.NO_SHOW);
    }

    @Test
    void noShowCannotBeRecordedBeforeTheAppointmentTime() {
        Appointment later = confirmedAt(LocalTime.of(16, 0));

        assertThatThrownBy(() -> service.markNoShow(later.getId()))
                .isInstanceOf(BusinessRuleException.class)
                .extracting("code").isEqualTo("TOO_EARLY");
        assertThat(appointmentStatus(later.getId())).isEqualTo(AppointmentStatus.CONFIRMED);
    }

    @Test
    void noShowTimeCheckUsesClinicLocalTime() {
        // 11:00 in Kolkata is 05:30 UTC and 22:30 the previous day in the JVM's zone
        // (tests run as America/Los_Angeles). An 11:15 appointment is still in the future.
        Appointment soon = confirmedAt(LocalTime.of(11, 15));

        assertThatThrownBy(() -> service.markNoShow(soon.getId())).isInstanceOf(BusinessRuleException.class);
    }

    @Test
    void unconfirmedBookingCannotBeNoShow() {
        Appointment booked = appointmentOn(doctor, patient(clinic, "Rahul"), TODAY);

        assertThatThrownBy(() -> service.markNoShow(booked.getId()))
                .isInstanceOf(InvalidStateTransitionException.class);
    }

    @Test
    void queuedAppointmentCannotBeChangedDirectly() {
        Appointment appointment = arrivedAppointment(doctor, patient(clinic, "Stepped Out"));
        var entry = queue.join(appointment.getId());
        queue.skip(entry.id());

        // SKIPPED -> NO_SHOW is a legal appointment transition, but doing it here would leave
        // the queue entry SKIPPED. It must go through the queue entry instead.
        assertThatThrownBy(() -> service.markNoShow(appointment.getId()))
                .isInstanceOf(BusinessRuleException.class)
                .extracting("code").isEqualTo("QUEUE_MANAGED");
        assertThatThrownBy(() -> service.cancel(appointment.getId()))
                .isInstanceOf(BusinessRuleException.class)
                .extracting("code").isEqualTo("QUEUE_MANAGED");

        assertThat(appointmentStatus(appointment.getId())).isEqualTo(AppointmentStatus.SKIPPED);
        assertThat(queue.get(entry.id()).status()).isEqualTo(QueueStatus.SKIPPED);
    }

    @Test
    void dayListingExcludesNextDaysMidnight() {
        Appointment lastSlot = appointmentOn(doctor, patient(clinic, "Late"), TODAY);
        lastSlot.setScheduledAt(LocalDateTime.of(TODAY, LocalTime.of(23, 59)));
        appointments.save(lastSlot);
        Appointment nextMidnight = appointmentOn(doctor, patient(clinic, "Midnight"), TODAY);
        nextMidnight.setScheduledAt(TODAY.plusDays(1).atStartOfDay());
        appointments.save(nextMidnight);

        assertThat(service.forDate(clinic.getId(), null, TODAY)).extracting("id").containsExactly(lastSlot.getId());
        assertThat(service.forDate(clinic.getId(), doctor.getId(), TODAY)).extracting("id")
                .containsExactly(lastSlot.getId());
        assertThat(service.forDate(clinic.getId(), null, TODAY.plusDays(1))).extracting("id")
                .containsExactly(nextMidnight.getId());
    }

    @Test
    void scheduledTimeIsStoredAsClinicWallClockRegardlessOfJvmZone() {
        // Regression: hibernate.jdbc.time_zone=UTC used to shift LocalDateTime by the JVM
        // offset, so 16:00 was stored as 10:30 on an IST server or 23:00 on a US one.
        Appointment appointment = confirmedAt(LocalTime.of(16, 0));

        String raw = jdbc.queryForObject(
                "select scheduled_at::text from appointments where id = ?", String.class, appointment.getId());
        assertThat(raw).isEqualTo(TODAY + " 16:00:00");
    }
}
