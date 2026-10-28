package com.clinicit.realtime;

import com.clinicit.clinic.domain.Clinic;
import com.clinicit.clinic.domain.DoctorProfile;
import com.clinicit.identity.domain.Actor;
import com.clinicit.identity.domain.Role;
import com.clinicit.queue.api.QueueEventMessage;
import com.clinicit.queue.application.QueueEventPublisher;
import com.clinicit.queue.application.QueueEventRecorder;
import com.clinicit.queue.application.QueueEventRelay;
import com.clinicit.queue.application.QueueService;
import com.clinicit.queue.domain.QueueEventRecordRepository;
import com.clinicit.support.RealtimeIntegrationTest;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.TestPropertySource;

import java.time.Duration;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * With several API instances, each one forwards events committed by the others to its own
 * WebSocket clients. "Another instance" is simulated by writing an outbox row directly, which
 * this JVM's dispatcher never sees.
 */
@TestPropertySource(properties = {
        "clinicit.queue.events.relay.enabled=true",
        // Ticks are driven by the tests.
        "clinicit.queue.events.relay.interval=PT1H"
})
class QueueEventRelayIntegrationTest extends RealtimeIntegrationTest {

    private static final Duration WINDOW = Duration.ofSeconds(30);

    @Autowired QueueService queue;
    @Autowired QueueEventRelay relayBean;
    @Autowired QueueEventPublisher publisher;
    @Autowired QueueEventRecordRepository outbox;

    Clinic clinic;
    DoctorProfile doctor;
    Actor desk;
    String deskToken;

    @BeforeEach
    void setUp() {
        clinic = clinic("City Clinic");
        doctor = doctor(clinic, "Dr. Sharma");
        desk = frontDesk(clinic);
        deskToken = login(staff(clinic, Role.RECEPTIONIST, null, "desk@city.test"));
    }

    /** An event as if committed by another instance: a copy of a real row with a new identity. */
    private long eventFromAnotherInstance(long template) {
        return jdbc.queryForObject("""
                insert into queue_events (event_id, clinic_id, doctor_id, queue_entry_id, appointment_id, queue_date,
                                          token_number, type, status, previous_status, entry_version, occurred_at)
                select gen_random_uuid(), clinic_id, doctor_id, queue_entry_id, appointment_id, queue_date,
                       token_number, type, status, previous_status, entry_version + 1, occurred_at
                from queue_events where id = ?
                returning id
                """, Long.class, template);
    }

    private long joinedEventId() {
        queue.join(desk, arrivedAppointment(doctor, patient(clinic, "A")).getId());
        return jdbc.queryForObject("select max(id) from queue_events", Long.class);
    }

    @Test
    void forwardsAnEventCommittedByAnotherInstanceExactlyOnce() throws Exception {
        QueueEventRelay relay = new QueueEventRelay(outbox, publisher, clock, WINDOW);
        relay.relay(clock.instant());
        BlockingQueue<JsonNode> events = subscribed(stomp(deskToken), clinicTopic(clinic.getId()));
        long local = joinedEventId();
        // A relay built by hand misses the after-commit notice the bean gets; pass it on.
        relay.deliveredLocally(new QueueEventRecorder.Recorded(QueueEventMessage.from(outbox.findById(local).orElseThrow())));
        next(events); // the dispatcher's own delivery of the local join

        long remote = eventFromAnotherInstance(local);

        assertThat(relay.relay(clock.instant())).isEqualTo(1);
        assertThat(next(events).get("sequence").asLong()).isEqualTo(remote);
        assertThat(relay.relay(clock.instant())).isZero();
        assertNothingReceived(events, Duration.ofMillis(300));
    }

    @Test
    void theRunningRelayDoesNotRepeatEventsThisInstanceAlreadyDelivered() throws Exception {
        relayBean.relay(clock.instant());
        BlockingQueue<JsonNode> events = subscribed(stomp(deskToken), clinicTopic(clinic.getId()));

        joinedEventId();
        next(events);

        assertThat(relayBean.relay(clock.instant())).isZero();
        assertNothingReceived(events, Duration.ofMillis(300));
    }

    @Test
    void doesNotReplayWhatWasAlreadyThereWhenTheInstanceStarted() throws Exception {
        long remote = eventFromAnotherInstance(joinedEventId());
        BlockingQueue<JsonNode> events = subscribed(stomp(deskToken), clinicTopic(clinic.getId()));

        QueueEventRelay relay = new QueueEventRelay(outbox, publisher, clock, WINDOW);
        assertThat(relay.relay(clock.instant())).isZero();
        assertThat(relay.relay(clock.instant())).isZero();
        assertNothingReceived(events, Duration.ofMillis(300));
        assertThat(outbox.findById(remote)).isPresent();
    }

    @Test
    void retriesAnEventItCouldNotPublish() {
        AtomicInteger attempts = new AtomicInteger();
        AtomicInteger published = new AtomicInteger();
        QueueEventPublisher failingOnce = event -> {
            if (attempts.incrementAndGet() == 1) {
                throw new IllegalStateException("broker unavailable");
            }
            published.incrementAndGet();
        };
        long local = joinedEventId();
        QueueEventRelay relay = new QueueEventRelay(outbox, failingOnce, clock, WINDOW);
        relay.relay(clock.instant());
        eventFromAnotherInstance(local);

        assertThat(relay.relay(clock.instant())).isZero();
        assertThat(relay.relay(clock.instant())).isEqualTo(1);
        assertThat(published).hasValue(1);
    }

    @Test
    void ignoresEventsOlderThanTheWindow() {
        long local = joinedEventId();
        QueueEventRelay relay = new QueueEventRelay(outbox, publisher, clock, WINDOW);
        relay.relay(clock.instant());
        eventFromAnotherInstance(local);

        assertThat(relay.relay(clock.instant().plus(WINDOW).plusSeconds(1))).isZero();
        assertThat(relay.relay(clock.instant())).as("still inside the window").isEqualTo(1);
    }
}
