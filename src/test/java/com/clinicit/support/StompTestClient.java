package com.clinicit.support;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.messaging.converter.CompositeMessageConverter;
import org.springframework.messaging.converter.MappingJackson2MessageConverter;
import org.springframework.messaging.converter.StringMessageConverter;
import org.springframework.messaging.simp.stomp.StompCommand;
import org.springframework.messaging.simp.stomp.StompFrameHandler;
import org.springframework.messaging.simp.stomp.StompHeaders;
import org.springframework.messaging.simp.stomp.StompSession;
import org.springframework.messaging.simp.stomp.StompSessionHandlerAdapter;
import org.springframework.web.socket.WebSocketHttpHeaders;
import org.springframework.web.socket.client.standard.StandardWebSocketClient;
import org.springframework.web.socket.messaging.WebSocketStompClient;

import java.lang.reflect.Type;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

/** A real STOMP-over-WebSocket client for tests, connected to the running server. */
public final class StompTestClient implements AutoCloseable {

    private final WebSocketStompClient client;
    private final BlockingQueue<String> errors = new LinkedBlockingQueue<>();
    private StompSession session;

    private StompTestClient() {
        client = new WebSocketStompClient(new StandardWebSocketClient());
        MappingJackson2MessageConverter json = new MappingJackson2MessageConverter();
        json.setObjectMapper(new ObjectMapper());
        client.setMessageConverter(new CompositeMessageConverter(List.of(new StringMessageConverter(), json)));
    }

    /** Connects with the given bearer token (null for none). Check {@link #isConnected()}. */
    public static StompTestClient connect(int port, String token) {
        StompTestClient test = new StompTestClient();
        StompHeaders connectHeaders = new StompHeaders();
        if (token != null) {
            connectHeaders.add("Authorization", "Bearer " + token);
        }
        try {
            test.session = test.client.connectAsync("ws://localhost:" + port + "/ws",
                    new WebSocketHttpHeaders(), connectHeaders, test.new Handler()).get(5, TimeUnit.SECONDS);
        } catch (Exception connectFailed) {
            test.errors.add("connect failed: " + connectFailed);
        }
        return test;
    }

    public boolean isConnected() {
        return session != null && session.isConnected();
    }

    /** Subscribes and returns the queue that received events (as raw JSON) land in. */
    public BlockingQueue<JsonNode> subscribe(String destination) {
        BlockingQueue<JsonNode> received = new LinkedBlockingQueue<>();
        session.subscribe(destination, new StompFrameHandler() {
            @Override
            public Type getPayloadType(StompHeaders headers) {
                return JsonNode.class;
            }

            @Override
            public void handleFrame(StompHeaders headers, Object payload) {
                received.add((JsonNode) payload);
            }
        });
        return received;
    }

    public void send(String destination, String body) {
        session.send(destination, body);
    }

    /** Waits for an ERROR frame or a lost connection. */
    public String awaitError(Duration timeout) throws InterruptedException {
        return errors.poll(timeout.toMillis(), TimeUnit.MILLISECONDS);
    }

    /** Waits until the server has closed the connection. */
    public boolean awaitDisconnected(Duration timeout) throws InterruptedException {
        long deadline = System.nanoTime() + timeout.toNanos();
        while (System.nanoTime() < deadline) {
            if (!isConnected()) {
                return true;
            }
            Thread.sleep(25);
        }
        return !isConnected();
    }

    @Override
    public void close() {
        if (isConnected()) {
            session.disconnect();
        }
        client.stop();
    }

    private class Handler extends StompSessionHandlerAdapter {
        @Override
        public Type getPayloadType(StompHeaders headers) {
            return String.class;
        }

        @Override
        public void handleFrame(StompHeaders headers, Object payload) {
            errors.add("ERROR frame: " + headers.getFirst("message"));
        }

        @Override
        public void handleException(StompSession s, StompCommand command, StompHeaders headers, byte[] payload, Throwable e) {
            errors.add("exception: " + e);
        }

        @Override
        public void handleTransportError(StompSession s, Throwable e) {
            errors.add("transport: " + e);
        }
    }
}
