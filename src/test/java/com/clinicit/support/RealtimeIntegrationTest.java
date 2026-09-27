package com.clinicit.support;

import com.clinicit.realtime.QueueTopics;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.AfterEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.messaging.simp.user.SimpUserRegistry;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/** Helpers for tests that talk to the running server over STOMP/WebSocket. */
public abstract class RealtimeIntegrationTest extends PostgresIntegrationTest {

    @Autowired protected SimpUserRegistry subscriptions;

    private final List<StompTestClient> clients = new ArrayList<>();

    @AfterEach
    void closeClients() {
        clients.forEach(StompTestClient::close);
        clients.clear();
    }

    protected StompTestClient stomp(String token) {
        StompTestClient client = StompTestClient.connect(port, token);
        clients.add(client);
        return client;
    }

    /** Subscribes and waits until the server has registered the subscription. */
    protected BlockingQueue<JsonNode> subscribed(StompTestClient client, String destination) throws InterruptedException {
        int before = countSubscriptions(destination);
        BlockingQueue<JsonNode> events = client.subscribe(destination);
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (countSubscriptions(destination) <= before) {
            assertThat(System.nanoTime()).as("subscription to %s registered", destination).isLessThan(deadline);
            Thread.sleep(10);
        }
        return events;
    }

    private int countSubscriptions(String destination) {
        return subscriptions.findSubscriptions(s -> destination.equals(s.getDestination())).size();
    }

    protected static String clinicTopic(UUID clinicId) {
        return QueueTopics.clinic(clinicId);
    }

    protected static String doctorTopic(UUID clinicId, UUID doctorId) {
        return QueueTopics.doctor(clinicId, doctorId);
    }

    protected static JsonNode next(BlockingQueue<JsonNode> events) throws InterruptedException {
        JsonNode event = events.poll(5, TimeUnit.SECONDS);
        assertThat(event).as("event received within 5s").isNotNull();
        return event;
    }

    /** Asserts nothing arrives for a while (for "must not receive" checks). */
    protected static void assertNothingReceived(BlockingQueue<JsonNode> events, Duration wait) throws InterruptedException {
        assertThat(events.poll(wait.toMillis(), TimeUnit.MILLISECONDS)).isNull();
    }
}
