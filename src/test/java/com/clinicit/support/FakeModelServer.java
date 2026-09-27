package com.clinicit.support;

import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Function;

/**
 * Stands in for the Python ML service in tests: a real HTTP server on a random port whose
 * answer each test chooses (a JSON body, an error status, or a delay). Records every request
 * body so tests can check what is (and isn't) sent.
 */
public final class FakeModelServer {

    public record Reply(int status, String body, long delayMillis) {
        public static Reply json(String body) { return new Reply(200, body, 0); }
        public static Reply error(int status) { return new Reply(status, "{\"code\":\"ERROR\"}", 0); }
        public static Reply slow(long millis, String body) { return new Reply(200, body, millis); }
    }

    private static FakeModelServer instance;

    private final HttpServer server;
    private final List<String> requests = new CopyOnWriteArrayList<>();
    private volatile Function<String, Reply> behaviour = body -> Reply.error(503);

    private FakeModelServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/predict/wait-time", exchange -> {
            String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            requests.add(body);
            Reply reply = behaviour.apply(body);
            if (reply.delayMillis() > 0) {
                try {
                    Thread.sleep(reply.delayMillis());
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
            byte[] bytes = reply.body().getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            try {
                exchange.sendResponseHeaders(reply.status(), bytes.length);
                try (OutputStream out = exchange.getResponseBody()) {
                    out.write(bytes);
                }
            } catch (IOException clientGaveUp) {
                // The client timed out and closed the connection: expected in timeout tests.
            }
        });
        server.setExecutor(java.util.concurrent.Executors.newCachedThreadPool());
        server.start();
    }

    public static synchronized FakeModelServer get() {
        if (instance == null) {
            try {
                instance = new FakeModelServer();
            } catch (IOException e) {
                throw new IllegalStateException(e);
            }
        }
        return instance;
    }

    public String baseUrl() {
        return "http://127.0.0.1:" + server.getAddress().getPort();
    }

    /** Answers every request with {@code behaviour(requestBody)}. Resets the request log. */
    public void respond(Function<String, Reply> behaviour) {
        this.behaviour = behaviour;
        requests.clear();
    }

    public List<String> requests() {
        return List.copyOf(requests);
    }

    /** A valid schema-1 response with one MODEL prediction per instance. */
    public static String modelAnswer(int instances, double estimate, double lower, double upper, String version) {
        StringBuilder predictions = new StringBuilder();
        for (int i = 0; i < instances; i++) {
            if (i > 0) predictions.append(',');
            predictions.append("""
                    {"estimatedWaitMinutes":%s,"lowerBoundMinutes":%s,"upperBoundMinutes":%s,"source":"MODEL","reason":null}"""
                    .formatted(estimate, lower, upper));
        }
        return """
                {"schemaVersion":"1","modelVersion":"%s","predictions":[%s]}""".formatted(version, predictions);
    }

    /** Number of feature rows in a request body. */
    public static int instanceCount(String body) {
        return body.split("\"patientsAhead\"", -1).length - 1;
    }
}
