import type { StompConfig } from "@stomp/stompjs";
import type { QueueEvent } from "@/api/types";

/**
 * Stand-in for @stomp/stompjs' Client (installed with vi.mock in each test file).
 * Lets a test connect, drop, reconnect and push events as the server would.
 */
export class FakeStompClient {
  static instances: FakeStompClient[] = [];
  readonly subscriptions: { destination: string; callback: (m: { body: string }) => void }[] = [];
  active = false;

  constructor(readonly config: StompConfig) {
    FakeStompClient.instances.push(this);
  }

  activate() {
    this.active = true;
  }

  deactivate() {
    this.active = false;
  }

  subscribe(destination: string, callback: (m: { body: string }) => void) {
    this.subscriptions.push({ destination, callback });
    return { unsubscribe: () => undefined };
  }

  /** The server accepted CONNECT. */
  connect() {
    this.config.beforeConnect?.(this as never);
    this.config.onConnect?.({} as never);
  }

  /** The socket dropped (the real client would then reconnect). */
  drop() {
    this.subscriptions.length = 0;
    this.config.onWebSocketClose?.({} as never);
  }

  /** The server sent a STOMP ERROR frame. */
  error(message: string) {
    this.config.onStompError?.({ headers: { message } } as never);
  }

  emit(event: QueueEvent) {
    for (const subscription of this.subscriptions) subscription.callback({ body: JSON.stringify(event) });
  }

  static latest(): FakeStompClient {
    const client = FakeStompClient.instances.at(-1);
    if (!client) throw new Error("No STOMP client was created");
    return client;
  }

  static reset() {
    FakeStompClient.instances = [];
  }
}
