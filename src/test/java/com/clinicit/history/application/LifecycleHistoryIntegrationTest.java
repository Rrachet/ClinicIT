package com.clinicit.history.application;

import com.clinicit.appointment.api.AppointmentResponse;
import com.clinicit.appointment.api.CreateAppointmentRequest;
import com.clinicit.appointment.application.AppointmentService;
import com.clinicit.appointment.domain.AppointmentStatus;
import com.clinicit.clinic.domain.Clinic;
import com.clinicit.clinic.domain.DoctorProfile;
import com.clinicit.common.domain.InvalidStateTransitionException;
import com.clinicit.identity.domain.Actor;
import com.clinicit.queue.api.QueueEntryResponse;
import com.clinicit.queue.application.QueueService;
import com.clinicit.support.PostgresIntegrationTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataAccessException;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class LifecycleHistoryIntegrationTest extends PostgresIntegrationTest {

    @Autowired AppointmentService appointmentService;
    @Autowired QueueService queue;
    @Autowired TransactionTemplate tx;
    @Autowired RequestMappingHandlerMapping handlerMapping;

    Clinic clinic;
    DoctorProfile doctor;
    Actor desk;
    Actor doc;

    @BeforeEach
    void setUp() {
        clinic = clinic("City Clinic");
        doctor = doctor(clinic, "Dr. Sharma");
        desk = frontDesk(clinic);
        doc = doctorActor(doctor);
    }

    record Event(
            String type, String previous, Instant at, UUID actor, UUID queueEntryId, Integer token,
            LocalDateTime scheduledAt, UUID doctorId, UUID patientId
    ) {}

    private List<Event> history(UUID appointmentId) {
        return jdbc.query("""
                select event_type, previous_status, occurred_at, actor_user_id, queue_entry_id, token_number,
                       scheduled_at, doctor_id, patient_id
                from operational_events where appointment_id = ? order by seq
                """, (rs, i) -> new Event(
                        rs.getString(1), rs.getString(2), rs.getTimestamp(3).toInstant(), rs.getObject(4, UUID.class),
                        rs.getObject(5, UUID.class), (Integer) rs.getObject(6),
                        rs.getObject(7, LocalDateTime.class), rs.getObject(8, UUID.class), rs.getObject(9, UUID.class)),
                appointmentId);
    }

    private List<String> types(UUID appointmentId) {
        return history(appointmentId).stream().map(Event::type).toList();
    }

    private int eventCount() {
        return jdbc.queryForObject("select count(*) from operational_events", Integer.class);
    }

    private Instant tick(int minutes) {
        clock.set(clock.instant().plus(Duration.ofMinutes(minutes)));
        return clock.instant();
    }

    private AppointmentResponse book(LocalTime at) {
        return appointmentService.create(desk, new CreateAppointmentRequest(
                patient(clinic, "Asha").getId(), doctor.getId(), LocalDateTime.of(TODAY, at), "Private reason"));
    }

    private QueueEntryResponse queued(LocalTime at) {
        UUID id = book(at).id();
        appointmentService.confirm(desk, id);
        appointmentService.arrive(desk, id);
        return queue.join(desk, id);
    }

    @Test
    void everyStepOfAVisitIsRecordedWithItsOwnTimeActorAndPreviousStatus() {
        Instant booked = clock.instant();
        AppointmentResponse appointment = book(LocalTime.of(11, 30));
        Instant confirmed = tick(1);
        appointmentService.confirm(desk, appointment.id());
        Instant arrived = tick(20);
        appointmentService.arrive(desk, appointment.id());
        Instant joined = tick(1);
        QueueEntryResponse entry = queue.join(desk, appointment.id());
        Instant called = tick(15);
        queue.callNext(doc, doctor.getId());
        Instant started = tick(2);
        queue.startConsultation(doc, entry.id());
        Instant completed = tick(12);
        queue.complete(doc, entry.id());

        List<Event> events = history(appointment.id());

        assertThat(events).extracting(Event::type).containsExactly(
                "BOOKED", "CONFIRMED", "ARRIVED", "WAITING", "CALLED", "IN_CONSULTATION", "COMPLETED");
        assertThat(events).extracting(Event::previous).containsExactly(
                null, "BOOKED", "CONFIRMED", "ARRIVED", "WAITING", "CALLED", "IN_CONSULTATION");
        assertThat(events).extracting(Event::at)
                .containsExactly(booked, confirmed, arrived, joined, called, started, completed);
        assertThat(events).extracting(Event::actor).containsExactly(
                desk.userId(), desk.userId(), desk.userId(), desk.userId(),
                doc.userId(), doc.userId(), doc.userId());
        // The queue entry and token are on queue events only; the scheduled time on BOOKED only.
        assertThat(events).extracting(Event::queueEntryId)
                .containsExactly(null, null, null, entry.id(), entry.id(), entry.id(), entry.id());
        assertThat(events.get(3).token()).isEqualTo(entry.tokenNumber());
        assertThat(events.get(0).scheduledAt()).isEqualTo(LocalDateTime.of(TODAY, LocalTime.of(11, 30)));
        assertThat(events).allSatisfy(event -> {
            assertThat(event.doctorId()).isEqualTo(doctor.getId());
            assertThat(event.patientId()).isEqualTo(appointment.patientId());
        });
    }

    @Test
    void skipRequeueAndNoShowAreRecordedAndKeepTheFirstCallTimeTheEntryForgets() {
        QueueEntryResponse entry = queued(LocalTime.of(11, 0));
        Instant firstCall = tick(10);
        queue.callNext(desk, doctor.getId());
        tick(2);
        queue.skip(desk, entry.id());
        tick(5);
        queue.requeue(desk, entry.id());
        tick(5);
        queue.callNext(desk, doctor.getId());
        tick(1);
        queue.skip(desk, entry.id());
        Instant noShow = tick(30);
        queue.markNoShow(desk, entry.id());

        List<Event> events = history(entry.appointmentId());
        assertThat(events).extracting(Event::type).containsExactly(
                "BOOKED", "CONFIRMED", "ARRIVED", "WAITING", "CALLED", "SKIPPED", "REQUEUED", "CALLED",
                "SKIPPED", "NO_SHOW");
        assertThat(events.get(6).previous()).isEqualTo("SKIPPED");
        assertThat(events.get(9).previous()).isEqualTo("SKIPPED");
        assertThat(events.get(9).at()).isEqualTo(noShow);

        // Requeueing clears the entry's called_at; the history still has the first call.
        assertThat(jdbc.queryForObject("select called_at from queue_entries where id = ?", Instant.class, entry.id()))
                .isNotEqualTo(firstCall);
        assertThat(events.get(4).at()).isEqualTo(firstCall);
    }

    @Test
    void cancellationsAndNoShowsBeforeTheQueueAreRecorded() {
        UUID cancelled = book(LocalTime.of(16, 0)).id();
        Instant cancelledAt = tick(3);
        appointmentService.cancel(desk, cancelled);

        UUID neverCame = book(LocalTime.of(11, 30)).id();
        appointmentService.confirm(desk, neverCame);
        tick(60);
        appointmentService.markNoShow(desk, neverCame);

        UUID leftTheDesk = book(LocalTime.of(12, 0)).id();
        appointmentService.confirm(desk, leftTheDesk);
        appointmentService.arrive(desk, leftTheDesk);
        appointmentService.markNoShow(desk, leftTheDesk);

        assertThat(types(cancelled)).containsExactly("BOOKED", "CANCELLED");
        assertThat(history(cancelled).get(1).at()).isEqualTo(cancelledAt);
        assertThat(history(neverCame)).extracting(Event::type, Event::previous).endsWith(
                org.assertj.core.groups.Tuple.tuple("NO_SHOW", "CONFIRMED"));
        assertThat(history(leftTheDesk)).extracting(Event::type, Event::previous).endsWith(
                org.assertj.core.groups.Tuple.tuple("NO_SHOW", "ARRIVED"));
    }

    @Test
    void rolledBackOperationsLeaveNoEvent() {
        QueueEntryResponse entry = queued(LocalTime.of(11, 0));
        int before = eventCount();

        // Succeeds inside the transaction, then the transaction rolls back.
        tx.executeWithoutResult(status -> {
            queue.callNext(desk, doctor.getId());
            status.setRollbackOnly();
        });
        assertThatThrownBy(() -> tx.executeWithoutResult(status -> {
            appointmentService.create(desk, new CreateAppointmentRequest(
                    patient(clinic, "Late").getId(), doctor.getId(), LocalDateTime.of(TODAY, LocalTime.NOON), null));
            throw new IllegalStateException("something later in the request failed");
        })).isInstanceOf(IllegalStateException.class);

        // Refused by the state machine: nothing is recorded.
        assertThatThrownBy(() -> queue.complete(desk, entry.id())).isInstanceOf(InvalidStateTransitionException.class);

        assertThat(eventCount()).isEqualTo(before);
        assertThat(appointmentStatus(entry.appointmentId())).isEqualTo(AppointmentStatus.WAITING);
    }

    @Test
    void eventsCannotBeChangedOrDeleted() {
        QueueEntryResponse entry = queued(LocalTime.of(11, 0));
        int before = eventCount();

        assertThatThrownBy(() -> jdbc.update("update operational_events set occurred_at = now()"))
                .isInstanceOf(DataAccessException.class)
                .hasMessageContaining("append-only");
        assertThatThrownBy(() -> jdbc.update(
                "update operational_events set event_type = 'COMPLETED' where queue_entry_id = ?", entry.id()))
                .isInstanceOf(DataAccessException.class)
                .hasMessageContaining("append-only");
        assertThatThrownBy(() -> jdbc.update("delete from operational_events"))
                .isInstanceOf(DataAccessException.class)
                .hasMessageContaining("append-only");

        assertThat(eventCount()).isEqualTo(before);
        assertThat(types(entry.appointmentId())).containsExactly("BOOKED", "CONFIRMED", "ARRIVED", "WAITING");
    }

    @Test
    void noApiCanWriteHistory() {
        handlerMapping.getHandlerMethods().forEach((mapping, handler) -> {
            assertThat(handler.getBeanType().getPackageName())
                    .as("the history module exposes no endpoints")
                    .doesNotStartWith("com.clinicit.history");
            if (handler.getBeanType().getPackageName().startsWith("com.clinicit.analytics")) {
                assertThat(mapping.getMethodsCondition().getMethods())
                        .as("analytics is read-only: %s", mapping)
                        .isEqualTo(Set.of(RequestMethod.GET));
            }
        });
    }
}
