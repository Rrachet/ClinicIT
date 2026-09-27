package com.clinicit.realtime;

import com.clinicit.clinic.domain.Clinic;
import com.clinicit.clinic.domain.DoctorProfile;
import com.clinicit.identity.domain.Actor;
import com.clinicit.identity.domain.Role;
import com.clinicit.identity.domain.UserAccount;
import com.clinicit.queue.application.QueueService;
import com.clinicit.support.RealtimeIntegrationTest;
import com.clinicit.support.StompTestClient;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.BlockingQueue;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * Who may connect, what they may subscribe to, and that connections die with their
 * session. Every denied subscription must end in an ERROR frame and a closed socket.
 */
class RealtimeSecurityIntegrationTest extends RealtimeIntegrationTest {

    private static final Duration WAIT = Duration.ofSeconds(5);

    @Autowired QueueService queue;
    @Autowired RealtimeConnections connections;
    @Autowired MockMvc mvc;

    Clinic clinicA;
    DoctorProfile sharmaA;
    DoctorProfile mehtaA;
    Actor deskA;
    UserAccount receptionistA;
    String receptionistTokenA;
    String sharmaTokenA;

    Clinic clinicB;
    DoctorProfile doctorB;
    Actor deskB;

    @BeforeEach
    void setUp() {
        clinicA = clinic("Clinic A");
        sharmaA = doctor(clinicA, "Dr. Sharma");
        mehtaA = doctor(clinicA, "Dr. Mehta");
        deskA = frontDesk(clinicA);
        receptionistA = staff(clinicA, Role.RECEPTIONIST, null, "desk@a.test");
        receptionistTokenA = login(receptionistA);
        sharmaTokenA = login(staff(clinicA, Role.DOCTOR, sharmaA, "sharma@a.test"));

        clinicB = clinic("Clinic B");
        doctorB = doctor(clinicB, "Dr. B");
        deskB = frontDesk(clinicB);
    }

    private void assertSubscriptionRefused(String token, String destination) throws InterruptedException {
        StompTestClient client = stomp(token);
        assertThat(client.isConnected()).isTrue();
        client.subscribe(destination);
        assertThat(client.awaitError(WAIT)).as("error for %s", destination).isNotNull();
        assertThat(client.awaitDisconnected(WAIT)).as("connection closed after refusing %s", destination).isTrue();
    }

    @Test
    void connectRequiresAValidToken() throws Exception {
        for (String token : new String[]{null, "not-a-real-token"}) {
            StompTestClient client = stomp(token);
            assertThat(client.awaitDisconnected(WAIT)).isTrue();
            assertThat(client.awaitError(WAIT)).isNotNull();
        }
    }

    @Test
    void cannotSubscribeToAnotherClinic() throws Exception {
        assertSubscriptionRefused(receptionistTokenA, clinicTopic(clinicB.getId()));
        assertSubscriptionRefused(receptionistTokenA, doctorTopic(clinicB.getId(), doctorB.getId()));
        // Own clinic in the path, other clinic's doctor: still refused.
        assertSubscriptionRefused(receptionistTokenA, doctorTopic(clinicA.getId(), doctorB.getId()));
        assertSubscriptionRefused(sharmaTokenA, doctorTopic(clinicB.getId(), doctorB.getId()));
    }

    @Test
    void anotherClinicsActivityNeverReachesYourSubscriptions() throws Exception {
        BlockingQueue<JsonNode> ownClinic = subscribed(stomp(receptionistTokenA), clinicTopic(clinicA.getId()));

        queue.join(deskB, arrivedAppointment(doctorB, patient(clinicB, "B patient")).getId());

        assertNothingReceived(ownClinic, Duration.ofMillis(500));
    }

    @Test
    void doctorsGetOnlyTheirOwnQueue() throws Exception {
        assertSubscriptionRefused(sharmaTokenA, clinicTopic(clinicA.getId()));
        assertSubscriptionRefused(sharmaTokenA, doctorTopic(clinicA.getId(), mehtaA.getId()));

        BlockingQueue<JsonNode> own = subscribed(stomp(sharmaTokenA), doctorTopic(clinicA.getId(), sharmaA.getId()));
        queue.join(deskA, arrivedAppointment(mehtaA, patient(clinicA, "Colleague's patient")).getId());
        var mine = queue.join(deskA, arrivedAppointment(sharmaA, patient(clinicA, "My patient")).getId());

        JsonNode event = next(own);
        assertThat(event.get("queueEntryId").asText()).isEqualTo(mine.id().toString());
        assertNothingReceived(own, Duration.ofMillis(300));
    }

    @Test
    void frontDeskCanFollowTheClinicOrAnyOfItsDoctors() throws Exception {
        StompTestClient client = stomp(receptionistTokenA);
        BlockingQueue<JsonNode> clinicWide = subscribed(client, clinicTopic(clinicA.getId()));
        BlockingQueue<JsonNode> mehta = subscribed(client, doctorTopic(clinicA.getId(), mehtaA.getId()));

        queue.join(deskA, arrivedAppointment(mehtaA, patient(clinicA, "P")).getId());

        assertThat(next(clinicWide).get("doctorId").asText()).isEqualTo(mehtaA.getId().toString());
        assertThat(next(mehta).get("doctorId").asText()).isEqualTo(mehtaA.getId().toString());
    }

    @Test
    void onlyKnownQueueTopicsCanBeSubscribed() throws Exception {
        for (String destination : List.of(
                "/topic/clinic/" + clinicA.getId() + "/queue/extra",
                "/topic/clinic/*/queue",
                "/topic/**",
                "/user/queue/anything",
                "/app/anything")) {
            assertSubscriptionRefused(receptionistTokenA, destination);
        }
    }

    @Test
    void clientsCannotSendMessages() throws Exception {
        StompTestClient client = stomp(receptionistTokenA);
        client.send("/app/queue/call-next", "{\"doctorId\":\"" + sharmaA.getId() + "\"}");

        assertThat(client.awaitError(WAIT)).isNotNull();
        assertThat(client.awaitDisconnected(WAIT)).isTrue();
    }

    @Test
    void logoutClosesTheConnection() throws Exception {
        StompTestClient client = stomp(receptionistTokenA);
        subscribed(client, clinicTopic(clinicA.getId()));

        mvc.perform(post("/api/v1/auth/logout").with(bearer(receptionistTokenA)));

        assertThat(client.awaitDisconnected(WAIT)).isTrue();
    }

    @Test
    void disablingTheUserClosesTheirConnections() throws Exception {
        String adminToken = login(staff(clinicA, Role.ADMIN, null, "admin@a.test"));
        StompTestClient client = stomp(receptionistTokenA);
        subscribed(client, clinicTopic(clinicA.getId()));
        StompTestClient adminClient = stomp(adminToken);
        subscribed(adminClient, clinicTopic(clinicA.getId()));

        mvc.perform(post("/api/v1/users/{id}/disable", receptionistA.getId()).with(bearer(adminToken)));

        assertThat(client.awaitDisconnected(WAIT)).isTrue();
        assertThat(adminClient.isConnected()).as("other users unaffected").isTrue();
    }

    @Test
    void passwordChangeClosesTheConnection() throws Exception {
        StompTestClient client = stomp(receptionistTokenA);
        subscribed(client, clinicTopic(clinicA.getId()));

        mvc.perform(post("/api/v1/auth/password").with(bearer(receptionistTokenA))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"currentPassword\":\"%s\",\"newPassword\":\"a different long passphrase\"}".formatted(PASSWORD)));

        assertThat(client.awaitDisconnected(WAIT)).isTrue();
    }

    @Test
    void expiredSessionsAreDisconnectedBySweep() throws Exception {
        StompTestClient client = stomp(receptionistTokenA);
        subscribed(client, clinicTopic(clinicA.getId()));

        connections.closeExpired();
        assertThat(client.isConnected()).as("still valid").isTrue();

        clock.set(NOW.plus(Duration.ofHours(12)));
        connections.closeExpired();

        assertThat(client.awaitDisconnected(WAIT)).isTrue();
    }

    @Test
    void handshakeFromAnUnknownBrowserOriginIsRejected() {
        var headers = new org.springframework.web.socket.WebSocketHttpHeaders();
        headers.setOrigin("https://evil.example");
        var client = new org.springframework.web.socket.messaging.WebSocketStompClient(
                new org.springframework.web.socket.client.standard.StandardWebSocketClient());
        var future = client.connectAsync("ws://localhost:" + port + "/ws", headers,
                new org.springframework.messaging.simp.stomp.StompHeaders(),
                new org.springframework.messaging.simp.stomp.StompSessionHandlerAdapter() {});

        org.assertj.core.api.Assertions.assertThatThrownBy(() -> future.get(5, java.util.concurrent.TimeUnit.SECONDS))
                .isInstanceOf(java.util.concurrent.ExecutionException.class);
        client.stop();
    }
}
