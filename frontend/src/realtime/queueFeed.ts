import { Client, type IFrame, type IMessage, type StompConfig } from "@stomp/stompjs";
import type { QueueEvent } from "@/api/types";

export type FeedStatus = "connecting" | "live" | "offline" | "denied";

/** The part of @stomp/stompjs' Client the feed uses (so tests can substitute a fake). */
export interface StompLike {
  activate(): void;
  deactivate(): unknown;
  subscribe(destination: string, callback: (message: Pick<IMessage, "body">) => void): unknown;
}

export interface QueueFeedOptions {
  url: string;
  token: string;
  /** A destination from docs/REALTIME.md, e.g. /topic/clinic/{clinicId}/queue */
  destination: string;
  onEvent: (event: QueueEvent) => void;
  /** Every successful (re)connect. Callers reload the REST board here: events are not replayed. */
  onConnected: (info: { reconnect: boolean }) => void;
  onStatus?: (status: FeedStatus) => void;
  /** The server refused the token (expired / revoked). */
  onAuthError?: () => void;
  createClient?: (config: StompConfig) => StompLike;
}

/**
 * The app's single WebSocket client. Read-only: it subscribes to one queue topic and
 * never sends. Reconnects automatically; each (re)connect is reported so the caller can
 * resynchronise from REST, which is the backend's rule for missed events.
 */
export function openQueueFeed(options: QueueFeedOptions): { close: () => void } {
  let connectedBefore = false;
  let closed = false;
  const status = (value: FeedStatus) => {
    if (!closed) options.onStatus?.(value);
  };

  const config: StompConfig = {
    brokerURL: options.url,
    connectHeaders: { Authorization: `Bearer ${options.token}` },
    reconnectDelay: 3000,
    heartbeatIncoming: 10000,
    heartbeatOutgoing: 10000,
    beforeConnect: () => status("connecting"),
    onConnect: () => {
      client.subscribe(options.destination, (message) => {
        const event = parseEvent(message.body);
        if (event) options.onEvent(event);
      });
      status("live");
      const reconnect = connectedBefore;
      connectedBefore = true;
      options.onConnected({ reconnect });
    },
    onStompError: (frame: IFrame) => {
      // The server sends ERROR and closes on a bad token or a refused subscription.
      // Retrying would not help, so stop instead of looping.
      closed = true;
      void client.deactivate();
      if ((frame.headers.message ?? "").toLowerCase().includes("authentication")) {
        options.onAuthError?.();
      } else {
        options.onStatus?.("denied");
      }
    },
    onWebSocketClose: () => status("offline"),
  };

  const client: StompLike = options.createClient ? options.createClient(config) : new Client(config);
  client.activate();

  return {
    close: () => {
      closed = true;
      void client.deactivate();
    },
  };
}

function parseEvent(body: string): QueueEvent | null {
  try {
    const event = JSON.parse(body) as QueueEvent;
    return event && typeof event.eventId === "string" && typeof event.entryVersion === "number" ? event : null;
  } catch {
    return null;
  }
}
