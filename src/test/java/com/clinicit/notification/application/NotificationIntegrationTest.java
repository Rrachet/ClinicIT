package com.clinicit.notification.application;

import com.clinicit.appointment.application.AppointmentService;
import com.clinicit.appointment.domain.Appointment;
import com.clinicit.appointment.domain.AppointmentStatus;
import com.clinicit.clinic.domain.Clinic;
import com.clinicit.clinic.domain.DoctorProfile;
import com.clinicit.identity.domain.Actor;
import com.clinicit.notification.domain.NotificationChannel;
import com.clinicit.notification.infrastructure.DevelopmentNotificationProvider;
import com.clinicit.patient.domain.Patient;
import com.clinicit.queue.api.QueueEntryResponse;
import com.clinicit.queue.application.QueueService;
import com.clinicit.support.PostgresIntegrationTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Duration;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Clinic/queue changes produce the right patient messages, delivered after commit. */
class NotificationIntegrationTest extends PostgresIntegrationTest {

    @Autowired QueueService queue;
    @Autowired AppointmentService appointmentService;
    @Autowired NotificationService notificationService;
    @Autowired NotificationDispatcher dispatcher;
    @Autowired DevelopmentNotificationProvider provider;
    @Autowired TransactionTemplate tx;
    @Autowired MockMvc mvc;

    Clinic clinic;
    DoctorProfile sharma;
    Actor desk;

    @BeforeEach
    void setUp() {
        provider.reset();
        clinic = clinic("City Clinic");
        sharma = doctor(clinic, "Dr. Sharma");
        desk = frontDesk(clinic);
    }

    private List<Map<String, Object>> rows() {
        return jdbc.queryForList("select * from notifications order by created_at, type");
    }

    private List<Map<String, Object>> rows(String type) {
        return jdbc.queryForList("select * from notifications where type = ? order by created_at", type);
    }

    private QueueEntryResponse join(String name) {
        return queue.join(desk, arrivedAppointment(sharma, patient(clinic, name)).getId());
    }

    @Test
    void confirmingALaterAppointmentSendsAConfirmation() {
        Appointment booked = appointmentOn(sharma, patient(clinic, "Rahul Kumar"), TODAY); // 16:00, now is 11:00

        appointmentService.confirm(desk, booked.getId());

        List<Map<String, Object>> sent = rows("APPOINTMENT_CONFIRMED");
        assertThat(sent).hasSize(1);
        assertThat(sent.getFirst())
                .containsEntry("status", "SENT")
                .containsEntry("channel", "SMS")
                .containsEntry("provider", "development")
                .containsEntry("body", "City Clinic: your appointment on 10 Mar at 16:00 is confirmed.");
        assertThat(provider.delivered()).hasSize(1);
    }

    @Test
    void walkInsAndImminentAppointmentsGetNoConfirmation() {
        Appointment soon = appointmentOn(sharma, patient(clinic, "Walk In"), TODAY);
        soon.setScheduledAt(LocalDateTime.of(TODAY, LocalTime.of(11, 20))); // 20 minutes from now
        appointments.save(soon);

        appointmentService.confirm(desk, soon.getId());

        assertThat(rows()).isEmpty();
    }

    @Test
    void joiningTheQueueSendsAWorkingStatusLink() throws Exception {
        QueueEntryResponse entry = join("Rahul Kumar");

        Map<String, Object> joined = rows("PATIENT_JOINED_QUEUE").getFirst();
        String expectedLink = FRONTEND_ORIGIN + "/status/" + entry.statusCode();
        assertThat(joined).containsEntry("status", "SENT");
        assertThat((String) joined.get("body"))
                .isEqualTo("City Clinic: you're checked in. Your token is #%d. Follow your place in the queue: %s"
                        .formatted(entry.tokenNumber(), expectedLink));

        // The link's code opens the public, read-only status page...
        mvc.perform(get("/api/v1/public/queue-status/{code}", entry.statusCode()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.tokenNumber").value(entry.tokenNumber()));
        // ...and stops working after the queue day, which is also when the message expires.
        assertThat(((java.sql.Timestamp) joined.get("expires_at")).toInstant())
                .isEqualTo(TODAY.plusDays(1).atStartOfDay(java.time.ZoneId.of("Asia/Kolkata")).toInstant());
        clock.set(NOW.plus(Duration.ofDays(1)));
        mvc.perform(get("/api/v1/public/queue-status/{code}", entry.statusCode())).andExpect(status().isNotFound());
    }

    @Test
    void callingSendsYourTurnAndWarnsThoseNearlyUpOnce() {
        QueueEntryResponse a = join("A");
        QueueEntryResponse b = join("B");
        QueueEntryResponse c = join("C");
        QueueEntryResponse d = join("D");

        queue.callNext(desk, sharma.getId());          // A called; B has 0 ahead, C has 1 ahead
        assertThat(rows("PATIENT_CALLED")).extracting(r -> r.get("queue_entry_id")).containsExactly(a.id());
        assertThat(rows("PATIENT_NEAR_TURN")).extracting(r -> r.get("queue_entry_id"))
                .containsExactlyInAnyOrder(b.id(), c.id());
        assertThat((String) rows("PATIENT_CALLED").getFirst().get("body"))
                .isEqualTo("City Clinic: token #%d, it's your turn. Please go in now.".formatted(a.tokenNumber()));

        queue.startConsultation(desk, a.id());
        queue.complete(desk, a.id());
        queue.callNext(desk, sharma.getId());          // B called; C now next (already warned); D now 1 ahead

        assertThat(rows("PATIENT_CALLED")).hasSize(2);
        assertThat(rows("PATIENT_NEAR_TURN")).extracting(r -> r.get("queue_entry_id"))
                .containsExactlyInAnyOrder(b.id(), c.id(), d.id());
    }

    @Test
    void minorQueueUpdatesDoNotMessageThePatient() {
        QueueEntryResponse a = join("A");
        queue.callNext(desk, sharma.getId());
        int before = rows().size();

        queue.skip(desk, a.id());          // called patient missed their turn
        queue.requeue(desk, a.id());
        queue.callNext(desk, sharma.getId());  // called again: this one IS meaningful
        queue.startConsultation(desk, a.id());
        queue.complete(desk, a.id());

        assertThat(rows()).hasSize(before + 1);
        assertThat(rows("PATIENT_CALLED")).hasSize(2);
    }

    @Test
    void theSameEventDeliveredTwiceCreatesOneMessage() {
        join("A");
        var event = com.clinicit.queue.api.QueueEventMessage.from(outboxEvent("PATIENT_JOINED_QUEUE"));

        tx.executeWithoutResult(status -> {
            notificationService.queueEventOccurred(event);
            notificationService.queueEventOccurred(event);
        });

        assertThat(rows("PATIENT_JOINED_QUEUE")).hasSize(1);
        assertThat(provider.delivered()).hasSize(1);
    }

    private com.clinicit.queue.domain.QueueEventRecord outboxEvent(String type) {
        return queueEvents.findAll().stream().filter(e -> e.getType().name().equals(type)).findFirst().orElseThrow();
    }

    @Autowired com.clinicit.queue.domain.QueueEventRecordRepository queueEvents;

    @Test
    void aProviderOutageNeverUndoesTheQueueChangeAndIsRetried() {
        join("A");
        provider.failNextSends(1);

        QueueEntryResponse called = queue.callNext(desk, sharma.getId());

        // The queue operation committed...
        assertThat(jdbc.queryForObject("select status from queue_entries where id = ?", String.class, called.id()))
                .isEqualTo("CALLED");
        // ...and the message waits for a retry.
        Map<String, Object> pending = rows("PATIENT_CALLED").getFirst();
        assertThat(pending).containsEntry("status", "PENDING").containsEntry("attempts", 1)
                .containsEntry("last_error", "SIMULATED_OUTAGE");
        assertThat(((java.sql.Timestamp) pending.get("next_attempt_at")).toInstant()).isEqualTo(NOW.plusSeconds(30));

        assertThat(dispatcher.retryDue(NOW.plusSeconds(10))).isZero();          // not due yet
        assertThat(dispatcher.retryDue(NOW.plusSeconds(31))).isEqualTo(1);
        assertThat(rows("PATIENT_CALLED").getFirst()).containsEntry("status", "SENT").containsEntry("attempts", 2);
    }

    @Test
    void givesUpAfterMaxAttempts() {
        provider.failNextSends(100);
        join("A");

        var at = NOW;
        for (int i = 0; i < 10 && "PENDING".equals(rows("PATIENT_JOINED_QUEUE").getFirst().get("status")); i++) {
            at = at.plus(Duration.ofHours(1));
            dispatcher.retryDue(at);
        }
        assertThat(rows("PATIENT_JOINED_QUEUE").getFirst())
                .containsEntry("status", "FAILED").containsEntry("attempts", 5).containsEntry("last_error", "SIMULATED_OUTAGE");
    }

    /** Runs the retry poller at `at`; returns true if the "process" died mid-send. */
    private boolean pollAndSurvive(java.time.Instant at) {
        try {
            dispatcher.retryDue(at);
            return false;
        } catch (DevelopmentNotificationProvider.SimulatedCrash crash) {
            return true;
        }
    }

    @Test
    void aSendThatDiesMidwayStillUsesAnAttemptSoItCannotRepeatForever() {
        provider.failNextSends(1); // the fast path after check-in hits an outage: attempt 1
        join("A");
        provider.crashNextSends(100, false); // every later attempt dies before the vendor accepts it

        int crashes = 0;
        var at = NOW;
        for (int i = 0; i < 20 && "PENDING".equals(rows("PATIENT_JOINED_QUEUE").getFirst().get("status")); i++) {
            at = at.plus(Duration.ofMinutes(3)); // past the backoff and the 2-minute claim lease
            if (pollAndSurvive(at)) crashes++;
        }

        // Attempts 2-5 died without reporting back; each was still counted, and then it stopped.
        assertThat(crashes).isEqualTo(4);
        assertThat(rows("PATIENT_JOINED_QUEUE").getFirst())
                .containsEntry("status", "FAILED").containsEntry("attempts", 5).containsEntry("last_error", "ATTEMPTS_EXHAUSTED");
        assertThat(provider.delivered()).isEmpty();
    }

    @Test
    void aCrashAfterTheVendorAcceptedIsRetriedWithTheSameIdempotencyKeySoThePatientGetsOneMessage() {
        provider.crashNextSends(1, true); // the fast path's send is accepted, then the sender dies
        join("A");
        UUID id = (UUID) rows("PATIENT_JOINED_QUEUE").getFirst().get("id");
        assertThat(rows("PATIENT_JOINED_QUEUE").getFirst()).containsEntry("status", "PENDING").containsEntry("attempts", 1);

        // Once the lease runs out, the poller tries again (at-least-once)...
        assertThat(pollAndSurvive(NOW.plus(Duration.ofMinutes(3)))).isFalse();

        // ...and the vendor, given the same key, does not deliver it twice.
        assertThat(rows("PATIENT_JOINED_QUEUE").getFirst()).containsEntry("status", "SENT").containsEntry("attempts", 2);
        assertThat(provider.delivered()).hasSize(1);
        assertThat(provider.delivered().getFirst().idempotencyKey()).isEqualTo(id.toString());
    }

    @Test
    void aClaimedMessageIsNotClaimedAgainWhileItsLeaseRuns() {
        provider.failNextSends(1);
        join("A");
        provider.crashNextSends(1, false);
        assertThat(pollAndSurvive(NOW.plus(Duration.ofMinutes(1)))).isTrue(); // claimed, died

        // Within the 2-minute lease nobody else picks it up, even though it is still PENDING.
        assertThat(dispatcher.retryDue(NOW.plus(Duration.ofMinutes(2)))).isZero();
        assertThat(dispatcher.retryDue(NOW.plus(Duration.ofMinutes(4)))).isEqualTo(1);
    }

    @Test
    void anOutOfDateMessageIsNeverSent() {
        join("A");
        provider.failNextSends(1);
        queue.callNext(desk, sharma.getId());
        provider.reset();

        // "Please go in now" is only true for a few minutes.
        dispatcher.retryDue(NOW.plus(Duration.ofMinutes(11)));

        assertThat(rows("PATIENT_CALLED").getFirst()).containsEntry("status", "FAILED").containsEntry("last_error", "EXPIRED");
        assertThat(provider.delivered()).isEmpty();
    }

    @Test
    void aRolledBackQueueChangeSendsNothing() {
        Appointment appointment = arrivedAppointment(sharma, patient(clinic, "Rolled Back"));

        tx.executeWithoutResult(status -> {
            queue.join(desk, appointment.getId());
            status.setRollbackOnly();
        });

        assertThat(rows()).isEmpty();
        assertThat(provider.delivered()).isEmpty();
    }

    @Test
    void messagesNeverContainClinicalOrPersonalDetails() {
        Patient patient = patient(clinic, "Sensitive Surname");
        jdbc.update("update patients set date_of_birth = '1980-05-17' where id = ?", patient.getId());
        jdbc.update("update doctor_profiles set specialization = 'Psychiatry' where id = ?", sharma.getId());
        Appointment appointment = appointmentOn(sharma, patient, TODAY);
        appointment.setReasonSummary("anxiety and chest pain");
        appointments.save(appointment);
        appointmentService.confirm(desk, appointment.getId());
        appointmentService.arrive(desk, appointment.getId());
        queue.join(desk, appointment.getId());
        queue.callNext(desk, sharma.getId());

        assertThat(rows()).hasSize(3).allSatisfy(row -> assertThat((String) row.get("body"))
                .doesNotContain("Sensitive").doesNotContain("Surname")
                .doesNotContain("anxiety").doesNotContain("chest")
                .doesNotContain("1980").doesNotContain("Psychiatry").doesNotContain("Sharma")
                .doesNotContain(patient.getPhone()));
    }

    @Test
    void noProviderForTheChannelFailsWithoutSending() {
        join("A");
        UUID id = jdbc.queryForObject("select id from notifications", UUID.class);
        jdbc.update("update notifications set status = 'PENDING', sent_at = null, attempts = 0, next_attempt_at = ? where id = ?",
                java.sql.Timestamp.from(NOW), id);
        NotificationProperties properties = new NotificationProperties(
                true, NotificationChannel.SMS, FRONTEND_ORIGIN, 1, 5, null, null, false, null);
        NotificationProvider whatsappOnly = new NotificationProvider() {
            public String name() { return "whatsapp-only"; }
            public boolean supports(NotificationChannel channel) { return channel == NotificationChannel.WHATSAPP; }
            public String send(OutboundMessage message) { throw new AssertionError("must not be called"); }
        };
        NotificationDispatcher onlyWhatsapp = new NotificationDispatcher(jdbc,
                new NotificationProviders(List.of(whatsappOnly), properties), properties,
                new NotificationExecutor(properties), clock);

        onlyWhatsapp.sendIfDue(id);

        assertThat(rows().getFirst()).containsEntry("status", "FAILED").containsEntry("last_error", "NO_PROVIDER_FOR_SMS");
    }

    @Test
    void deliveredMessagesArePurgedAfterRetention() {
        join("A");
        assertThat(dispatcher.purge(NOW.plus(Duration.ofDays(29)))).isZero();
        assertThat(dispatcher.purge(NOW.plus(Duration.ofDays(31)))).isEqualTo(1);
    }

    @Test
    void disabledNotificationsCreateNothing() {
        NotificationProperties off = new NotificationProperties(false, null, FRONTEND_ORIGIN, null, null, null, null, false, null);
        NotificationService disabled = new NotificationService(jdbc, off, event -> { }, clock);
        Appointment appointment = appointmentOn(sharma, patient(clinic, "A"), TODAY, AppointmentStatus.CONFIRMED);

        tx.executeWithoutResult(status -> disabled.appointmentConfirmed(appointment.getId()));

        assertThat(rows()).isEmpty();
    }
}
