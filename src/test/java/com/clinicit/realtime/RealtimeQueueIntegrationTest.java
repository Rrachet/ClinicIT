package com.clinicit.realtime;

import com.clinicit.appointment.domain.Appointment;
import com.clinicit.clinic.domain.Clinic;
import com.clinicit.clinic.domain.DoctorProfile;
import com.clinicit.identity.domain.Actor;
import com.clinicit.identity.domain.Role;
import com.clinicit.patient.domain.Patient;
import com.clinicit.queue.api.QueueEntryResponse;
import com.clinicit.queue.application.QueueEventDispatcher;
import com.clinicit.queue.application.QueueEventRecorder;
import com.clinicit.queue.application.QueueService;
import com.clinicit.queue.domain.QueueEventRecordRepository;
import com.clinicit.support.RealtimeIntegrationTest;
import com.clinicit.support.StompTestClient;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.PlatformTransactionManager;

import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Queue actions over HTTP produce the right events on the WebSocket, and only after commit. */
class RealtimeQueueIntegrationTest extends RealtimeIntegrationTest {

    private static final Set<String> CONTRACT_FIELDS = Set.of(
            "eventId", "sequence", "type", "occurredAt", "clinicId", "doctorId", "queueDate",
            "queueEntryId", "appointmentId", "tokenNumber", "status", "previousStatus", "entryVersion");

    @Autowired MockMvc mvc;
    @Autowired QueueService queue;
    @Autowired QueueEventDispatcher dispatcher;
    @Autowired QueueEventRecordRepository outbox;
    @Autowired PlatformTransactionManager transactionManager;
    @Autowired JdbcTemplate jdbcTemplate;

    Clinic clinic;
    DoctorProfile sharma;
    DoctorProfile mehta;
    Actor desk;
    String deskToken;

    @BeforeEach
    void setUp() {
        clinic = clinic("City Clinic");
        sharma = doctor(clinic, "Dr. Sharma");
        mehta = doctor(clinic, "Dr. Mehta");
        desk = frontDesk(clinic);
        deskToken = login(staff(clinic, Role.RECEPTIONIST, null, "desk@city.test"));
    }

    private void http(String path) throws Exception {
        mvc.perform(post(path).with(bearer(deskToken))).andExpect(status().isOk());
    }

    private String joinViaHttp(Appointment appointment) throws Exception {
        String body = mvc.perform(post("/api/v1/queue-entries").with(bearer(deskToken))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"appointmentId\":\"%s\"}".formatted(appointment.getId())))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        return new com.fasterxml.jackson.databind.ObjectMapper().readTree(body).get("id").asText();
    }

    @Test
    void eachQueueActionChangesTheDatabaseAndEmitsTheMatchingEvent() throws Exception {
        StompTestClient client = stomp(deskToken);
        BlockingQueue<JsonNode> events = subscribed(client, clinicTopic(clinic.getId()));

        Patient patient = patient(clinic, "Rahul Kumar");
        Appointment appointment = arrivedAppointment(sharma, patient);
        String entryId = joinViaHttp(appointment);

        JsonNode joined = next(events);
        assertThat(joined.get("type").asText()).isEqualTo("PATIENT_JOINED_QUEUE");
        assertThat(joined.get("queueEntryId").asText()).isEqualTo(entryId);
        assertThat(joined.get("appointmentId").asText()).isEqualTo(appointment.getId().toString());
        assertThat(joined.get("clinicId").asText()).isEqualTo(clinic.getId().toString());
        assertThat(joined.get("doctorId").asText()).isEqualTo(sharma.getId().toString());
        assertThat(joined.get("tokenNumber").asInt()).isEqualTo(1);
        assertThat(joined.get("queueDate").asText()).isEqualTo(TODAY.toString());
        assertThat(joined.get("status").asText()).isEqualTo("WAITING");
        assertThat(joined.get("previousStatus").isNull()).isTrue();
        assertThat(joined.get("entryVersion").asLong()).isZero();

        mvc.perform(post("/api/v1/queues/call-next").with(bearer(deskToken))
                .contentType(MediaType.APPLICATION_JSON).content("{\"doctorId\":\"%s\"}".formatted(sharma.getId())))
                .andExpect(status().isOk());
        http("/api/v1/queue-entries/" + entryId + "/start");
        http("/api/v1/queue-entries/" + entryId + "/complete");

        List<String> types = new ArrayList<>(List.of(joined.get("type").asText()));
        long lastVersion = 0;
        for (String expectedStatus : List.of("CALLED", "IN_CONSULTATION", "COMPLETED")) {
            JsonNode event = next(events);
            types.add(event.get("type").asText());
            assertThat(event.get("status").asText()).isEqualTo(expectedStatus);
            assertThat(event.get("entryVersion").asLong()).isGreaterThan(lastVersion);
            lastVersion = event.get("entryVersion").asLong();
        }
        assertThat(types).containsExactly(
                "PATIENT_JOINED_QUEUE", "PATIENT_CALLED", "PATIENT_STARTED_CONSULTATION", "PATIENT_COMPLETED");

        // The database agrees with the last event, and every outbox row was marked published.
        assertThat(jdbcTemplate.queryForObject("select status from queue_entries where id = ?::uuid", String.class, entryId))
                .isEqualTo("COMPLETED");
        assertThat(jdbcTemplate.queryForObject("select count(*) from queue_events where published_at is null", Integer.class))
                .isZero();
        assertThat(outbox.count()).isEqualTo(4);
    }

    @Test
    void skipRequeueAndNoShowEmitTheirEvents() throws Exception {
        BlockingQueue<JsonNode> events = subscribed(stomp(deskToken), doctorTopic(clinic.getId(), sharma.getId()));
        QueueEntryResponse entry = queue.join(desk, arrivedAppointment(sharma, patient(clinic, "A")).getId());
        queue.skip(desk, entry.id());
        queue.requeue(desk, entry.id());
        queue.skip(desk, entry.id());
        queue.markNoShow(desk, entry.id());

        List<String> received = new ArrayList<>();
        for (int i = 0; i < 5; i++) {
            JsonNode event = next(events);
            received.add(event.get("type").asText() + ":" + event.get("previousStatus").asText(null));
        }
        assertThat(received).containsExactly(
                "PATIENT_JOINED_QUEUE:null", "PATIENT_SKIPPED:WAITING", "PATIENT_REQUEUED:SKIPPED",
                "PATIENT_SKIPPED:WAITING", "PATIENT_NO_SHOW:SKIPPED");
    }

    @Test
    void eventsCarryNoPatientData() throws Exception {
        BlockingQueue<JsonNode> events = subscribed(stomp(deskToken), clinicTopic(clinic.getId()));
        Patient patient = patient(clinic, "Sensitive Name");
        Appointment appointment = arrivedAppointment(sharma, patient);
        appointment.setReasonSummary("chest pain since tuesday");
        appointments.save(appointment);

        queue.join(desk, appointment.getId());
        JsonNode event = next(events);

        Set<String> fields = new HashSet<>();
        event.fieldNames().forEachRemaining(fields::add);
        assertThat(fields).isEqualTo(CONTRACT_FIELDS);
        assertThat(event.toString())
                .doesNotContain("Sensitive Name")
                .doesNotContain(patient.getPhone())
                .doesNotContain(patient.getId().toString())
                .doesNotContain("chest pain");
    }

    @Test
    void rejectedActionsEmitNothing() throws Exception {
        BlockingQueue<JsonNode> events = subscribed(stomp(deskToken), clinicTopic(clinic.getId()));

        // Business-rule failures roll back: no outbox row, no event.
        mvc.perform(post("/api/v1/queues/call-next").with(bearer(deskToken))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"doctorId\":\"%s\"}".formatted(sharma.getId())))
                .andExpect(status().isConflict());
        Appointment notArrived = appointmentOn(sharma, patient(clinic, "Early"), TODAY);
        mvc.perform(post("/api/v1/queue-entries").with(bearer(deskToken)).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"appointmentId\":\"%s\"}".formatted(notArrived.getId())))
                .andExpect(status().isConflict());

        assertNothingReceived(events, Duration.ofMillis(500));
        assertThat(outbox.count()).isZero();
    }

    @Test
    void workRolledBackAfterTheEventWasRecordedIsNeverAnnounced() throws Exception {
        BlockingQueue<JsonNode> events = subscribed(stomp(deskToken), clinicTopic(clinic.getId()));
        Appointment appointment = arrivedAppointment(sharma, patient(clinic, "Rolled Back"));

        // The join (and its outbox row) happen, then the surrounding transaction rolls back.
        new org.springframework.transaction.support.TransactionTemplate(transactionManager).executeWithoutResult(tx -> {
            queue.join(desk, appointment.getId());
            tx.setRollbackOnly();
        });

        assertNothingReceived(events, Duration.ofMillis(500));
        assertThat(outbox.count()).isZero();
        assertThat(appointmentStatus(appointment.getId()).name()).isEqualTo("ARRIVED");
    }

    @Test
    void eventOnlyLeavesAfterCommitAndDeliveryFailureDoesNotUndoTheChange() throws Exception {
        // A publisher that is down: the queue change must still commit, the event stays pending.
        QueueEventDispatcher failing = new QueueEventDispatcher(outbox, event -> {
            throw new IllegalStateException("broker down");
        }, transactionManager, jdbcTemplate, clock, Duration.ofSeconds(10), Duration.ofDays(7));
        BlockingQueue<JsonNode> events = subscribed(stomp(deskToken), clinicTopic(clinic.getId()));

        QueueEntryResponse entry = queue.join(desk, arrivedAppointment(sharma, patient(clinic, "A")).getId());
        next(events); // delivered by the real dispatcher
        jdbcTemplate.update("update queue_events set published_at = null");
        failing.afterCommit(new QueueEventRecorder.Recorded(
                com.clinicit.queue.api.QueueEventMessage.from(outbox.findAll().getFirst())));
        assertThat(jdbcTemplate.queryForObject("select count(*) from queue_events where published_at is null", Integer.class))
                .isEqualTo(1);
        assertThat(queue.get(desk, entry.id()).status().name()).isEqualTo("WAITING");

        // Not yet due for retry: the poller leaves it alone.
        assertThat(dispatcher.retryPending(clock.instant())).isZero();

        // Once due, the poller re-sends it; clients see the same eventId again (at-least-once).
        String originalEventId = outbox.findAll().getFirst().getEventId().toString();
        assertThat(dispatcher.retryPending(clock.instant().plusSeconds(11))).isEqualTo(1);
        assertThat(next(events).get("eventId").asText()).isEqualTo(originalEventId);
        assertThat(jdbcTemplate.queryForObject("select count(*) from queue_events where published_at is null", Integer.class))
                .isZero();
    }

    @Test
    void publishedEventsArePurgedAfterRetention() {
        queue.join(desk, arrivedAppointment(sharma, patient(clinic, "A")).getId());

        assertThat(dispatcher.purgePublished(clock.instant())).isZero();
        assertThat(dispatcher.purgePublished(clock.instant().plus(Duration.ofDays(8)))).isEqualTo(1);
    }

    @Test
    void concurrentActionsYieldACompleteEventStreamThatReplaysToTheDatabaseState() throws Exception {
        BlockingQueue<JsonNode> events = subscribed(stomp(deskToken), clinicTopic(clinic.getId()));
        List<DoctorProfile> doctorList = List.of(sharma, mehta);
        List<UUID> appointmentIds = new ArrayList<>();
        for (int i = 0; i < 16; i++) {
            appointmentIds.add(arrivedAppointment(doctorList.get(i % 2), patient(clinic, "P" + i)).getId());
        }

        ExecutorService pool = Executors.newFixedThreadPool(8);
        CountDownLatch go = new CountDownLatch(1);
        List<java.util.concurrent.Future<?>> work = new ArrayList<>();
        for (UUID appointmentId : appointmentIds) {
            work.add(pool.submit(() -> {
                go.await();
                QueueEntryResponse entry = queue.join(desk, appointmentId);
                queue.skip(desk, entry.id());
                queue.requeue(desk, entry.id());
                return null;
            }));
        }
        for (int i = 0; i < 6; i++) {
            DoctorProfile doctor = doctorList.get(i % 2);
            work.add(pool.submit(() -> {
                go.await();
                try {
                    var called = queue.callNext(desk, doctor.getId());
                    queue.startConsultation(desk, called.id());
                    queue.complete(desk, called.id());
                } catch (com.clinicit.common.domain.BusinessRuleException raceLost) {
                    // doctor busy or nobody waiting yet
                }
                return null;
            }));
        }
        go.countDown();
        for (var future : work) {
            future.get(60, TimeUnit.SECONDS);
        }
        pool.shutdown();

        int expected = (int) outbox.count();
        List<JsonNode> received = new ArrayList<>();
        while (received.size() < expected) {
            received.add(next(events));
        }
        assertNothingReceived(events, Duration.ofMillis(300));

        // Exactly one delivery per committed change, no duplicates.
        assertThat(received.stream().map(e -> e.get("eventId").asText()).distinct()).hasSize(expected);

        // Arrival order across threads is not guaranteed; the entryVersion rule makes it safe:
        // keep an event only if it is newer than what the client already has.
        Map<String, JsonNode> latest = new HashMap<>();
        Map<String, Set<Long>> versions = new HashMap<>();
        for (JsonNode event : received) {
            String entry = event.get("queueEntryId").asText();
            versions.computeIfAbsent(entry, k -> new HashSet<>()).add(event.get("entryVersion").asLong());
            JsonNode known = latest.get(entry);
            if (known == null || event.get("entryVersion").asLong() > known.get("entryVersion").asLong()) {
                latest.put(entry, event);
            }
        }
        assertThat(latest).hasSize(appointmentIds.size());
        for (Map.Entry<String, JsonNode> entry : latest.entrySet()) {
            String dbStatus = jdbcTemplate.queryForObject(
                    "select status from queue_entries where id = ?::uuid", String.class, entry.getKey());
            assertThat(entry.getValue().get("status").asText()).as("replayed state of %s", entry.getKey())
                    .isEqualTo(dbStatus);
            // Versions per entry are contiguous: no change was lost or reported twice.
            Set<Long> seen = versions.get(entry.getKey());
            assertThat(seen).hasSize((int) entry.getValue().get("entryVersion").asLong() + 1);
        }
    }
}
