import { afterEach, describe, expect, it, vi } from "vitest";
import type { StompConfig } from "@stomp/stompjs";
import { event } from "@/test/fixtures";
import { FakeStompClient } from "@/test/fakeStomp";
import { openQueueFeed } from "./queueFeed";

const createClient = (config: StompConfig) => new FakeStompClient(config);

describe("openQueueFeed", () => {
  afterEach(() => FakeStompClient.reset());

  function open(overrides: Partial<Parameters<typeof openQueueFeed>[0]> = {}) {
    const handlers = { onEvent: vi.fn(), onConnected: vi.fn(), onStatus: vi.fn(), onAuthError: vi.fn() };
    const feed = openQueueFeed({ url: "ws://api.test/ws", token: "tok", destination: "/topic/clinic/c1/queue", createClient, ...handlers, ...overrides });
    return { feed, client: FakeStompClient.latest(), ...handlers };
  }

  it("authenticates in the CONNECT frame and subscribes read-only to one topic", () => {
    const { client, onStatus } = open();
    expect(client.config.connectHeaders).toEqual({ Authorization: "Bearer tok" });
    expect(client.active).toBe(true);

    client.connect();
    expect(client.subscriptions.map((s) => s.destination)).toEqual(["/topic/clinic/c1/queue"]);
    expect(onStatus).toHaveBeenLastCalledWith("live");
  });

  it("passes events through and drops malformed messages", () => {
    const { client, onEvent } = open();
    client.connect();
    const called = event({ queueEntryId: "a", entryVersion: 1 });
    client.emit(called);
    client.subscriptions[0].callback({ body: "not json" });
    client.subscriptions[0].callback({ body: JSON.stringify({ hello: "world" }) });
    expect(onEvent).toHaveBeenCalledTimes(1);
    expect(onEvent).toHaveBeenCalledWith(called);
  });

  it("reports reconnects so the caller can reload the REST board", () => {
    const { client, onConnected, onStatus } = open();
    client.connect();
    client.drop();
    expect(onStatus).toHaveBeenLastCalledWith("offline");
    client.connect();
    expect(onConnected.mock.calls).toEqual([[{ reconnect: false }], [{ reconnect: true }]]);
    expect(client.subscriptions).toHaveLength(1); // resubscribed after reconnect
  });

  it("stops (no retry loop) and signals when the server rejects the token", () => {
    const { client, onAuthError } = open();
    client.error("Authentication required");
    expect(onAuthError).toHaveBeenCalledOnce();
    expect(client.active).toBe(false);
  });

  it("stops and reports 'denied' when a subscription is refused", () => {
    const { client, onStatus, onAuthError } = open();
    client.connect();
    client.error("Access denied");
    expect(onStatus).toHaveBeenLastCalledWith("denied");
    expect(onAuthError).not.toHaveBeenCalled();
    expect(client.active).toBe(false);
  });

  it("close() deactivates the client", () => {
    const { feed, client } = open();
    feed.close();
    expect(client.active).toBe(false);
  });
});
