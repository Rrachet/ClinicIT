package com.clinicit.support;

import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Stands in for the Claude Messages API in tests: answers POST /v1/messages with the replies a
 * test queues, in order, and records every request body and its anthropic-beta header. The
 * real Anthropic Java SDK talks to it over HTTP, so requests and responses are the true wire format.
 */
public final class FakeClaudeServer {

    public record Request(String body, String betaHeader) {}

    private static FakeClaudeServer instance;

    private final HttpServer server;
    private final Deque<Map.Entry<Integer, String>> replies = new ArrayDeque<>();
    private final List<Request> requests = new CopyOnWriteArrayList<>();

    private FakeClaudeServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/messages", exchange -> {
            String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            requests.add(new Request(body, exchange.getRequestHeaders().getFirst("anthropic-beta")));
            Map.Entry<Integer, String> reply;
            synchronized (replies) {
                reply = replies.isEmpty()
                        ? Map.entry(500, "{\"type\":\"error\",\"error\":{\"type\":\"api_error\",\"message\":\"no reply queued\"}}")
                        : replies.removeFirst();
            }
            byte[] bytes = reply.getValue().getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(reply.getKey(), bytes.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(bytes);
            }
        });
        server.setExecutor(java.util.concurrent.Executors.newCachedThreadPool());
        server.start();
    }

    public static synchronized FakeClaudeServer get() {
        if (instance == null) {
            try {
                instance = new FakeClaudeServer();
            } catch (IOException e) {
                throw new IllegalStateException(e);
            }
        }
        return instance;
    }

    public String baseUrl() {
        return "http://127.0.0.1:" + server.getAddress().getPort();
    }

    /** Forgets queued replies and recorded requests. */
    public void reset() {
        synchronized (replies) {
            replies.clear();
        }
        requests.clear();
    }

    public void reply(String messageJson) {
        reply(200, messageJson);
    }

    public void reply(int status, String body) {
        synchronized (replies) {
            replies.addLast(Map.entry(status, body));
        }
    }

    public List<Request> requests() {
        return List.copyOf(requests);
    }

    /** An assistant message as the Messages API returns it. */
    public static String message(String stopReason, String contentJson) {
        return """
                {"id":"msg_test","type":"message","role":"assistant","model":"claude-opus-5-5",
                 "content":%s,"stop_reason":"%s","stop_sequence":null,
                 "usage":{"input_tokens":100,"output_tokens":20}}
                """.formatted(contentJson, stopReason);
    }

    public static String text(String text) {
        return "{\"type\":\"text\",\"text\":%s}".formatted(quote(text));
    }

    public static String toolUse(String id, String name, String inputJson) {
        return "{\"type\":\"tool_use\",\"id\":\"%s\",\"name\":\"%s\",\"input\":%s}".formatted(id, name, inputJson);
    }

    public static String thinking(String signature) {
        return "{\"type\":\"thinking\",\"thinking\":\"\",\"signature\":\"%s\"}".formatted(signature);
    }

    private static String quote(String s) {
        return "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
    }
}
