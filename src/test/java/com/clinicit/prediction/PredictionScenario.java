package com.clinicit.prediction;

import com.clinicit.appointment.api.CreateAppointmentRequest;
import com.clinicit.appointment.application.AppointmentService;
import com.clinicit.clinic.domain.Clinic;
import com.clinicit.clinic.domain.DoctorProfile;
import com.clinicit.identity.domain.Actor;
import com.clinicit.queue.api.QueueEntryResponse;
import com.clinicit.queue.application.QueueService;
import com.clinicit.support.PostgresIntegrationTest;
import org.springframework.beans.factory.annotation.Autowired;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.UUID;

/** Scripted clinic histories driven through the real services with a controlled clock. */
abstract class PredictionScenario extends PostgresIntegrationTest {

    static final ZoneId CLINIC_ZONE = ZoneId.of("Asia/Kolkata");

    @Autowired AppointmentService appointmentService;
    @Autowired QueueService queue;

    Clinic clinic;
    DoctorProfile drA;
    Actor desk;

    void setUpClinic() {
        clinic = clinic("City Clinic");
        drA = doctor(clinic, "Dr. A");
        desk = frontDesk(clinic);
    }

    Instant at(LocalDate date, int hour, int minute) {
        Instant instant = LocalDateTime.of(date, LocalTime.of(hour, minute)).atZone(CLINIC_ZONE).toInstant();
        clock.set(instant);
        return instant;
    }

    Instant at(int hour, int minute) {
        return at(TODAY, hour, minute);
    }

    /** Books, confirms, marks arrived and joins the queue, all at the current clock time. */
    QueueEntryResponse checkIn(DoctorProfile doctor) {
        LocalDateTime now = LocalDateTime.ofInstant(clock.instant(), CLINIC_ZONE);
        UUID id = appointmentService.create(desk, new CreateAppointmentRequest(
                patient(clinic, "Asha Rao").getId(), doctor.getId(), now, null)).id();
        appointmentService.confirm(desk, id);
        appointmentService.arrive(desk, id);
        return queue.join(desk, id);
    }

    /** Call next, start, and later complete; returns the called entry. */
    QueueEntryResponse callAndStart(DoctorProfile doctor) {
        QueueEntryResponse called = queue.callNext(desk, doctor.getId());
        queue.startConsultation(desk, called.id());
        return called;
    }

    long lastSeq() {
        return jdbc.queryForObject("select max(seq) from operational_events", Long.class);
    }

    long joinSeq(UUID entryId) {
        return jdbc.queryForObject(
                "select seq from operational_events where queue_entry_id = ? and event_type = 'WAITING'",
                Long.class, entryId);
    }
}
