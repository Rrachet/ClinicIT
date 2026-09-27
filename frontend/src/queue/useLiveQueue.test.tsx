import { act, screen, waitFor } from "@testing-library/react";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { fakeApi } from "@/test/fakeApi";
import { FakeStompClient } from "@/test/fakeStomp";
import { board, event, row, session, SHARMA } from "@/test/fixtures";
import { navigationMock } from "@/test/navigation";
import { renderWithAuth } from "@/test/render";
import { doctorView } from "./queueStore";
import { useLiveQueue } from "./useLiveQueue";

vi.mock("next/navigation", () => navigationMock);
vi.mock("@stomp/stompjs", async () => ({ Client: (await import("@/test/fakeStomp")).FakeStompClient }));

function Probe({ onChange }: { onChange?: () => void }) {
  const live = useLiveQueue({ doctorIds: [SHARMA.id], destination: "/topic/clinic/clinic-1/queue", onChange });
  const view = doctorView(live.queue, SHARMA.id);
  return (
    <div>
      <p data-testid="current">{view.current ? `#${view.current.tokenNumber} ${view.current.status}` : "none"}</p>
      <p data-testid="waiting">{view.waiting.map((i) => `#${i.tokenNumber}:${i.patientName}`).join(",")}</p>
      <p data-testid="feed">{live.feedStatus}</p>
    </div>
  );
}

describe("useLiveQueue", () => {
  let api: ReturnType<typeof fakeApi>;
  let serverBoard = board(SHARMA.id, [row({ id: "a", tokenNumber: 1 }), row({ id: "b", tokenNumber: 2 })]);

  beforeEach(() => {
    FakeStompClient.reset();
    serverBoard = board(SHARMA.id, [row({ id: "a", tokenNumber: 1 }), row({ id: "b", tokenNumber: 2 })]);
    api = fakeApi().route("GET /api/v1/queues/today", () => ({ body: serverBoard }));
    vi.stubGlobal("fetch", api.fetchMock);
  });
  afterEach(() => vi.unstubAllGlobals());

  async function mounted(onChange?: () => void) {
    renderWithAuth(<Probe onChange={onChange} />, { session: session("RECEPTIONIST") });
    await waitFor(() => expect(screen.getByTestId("waiting")).toHaveTextContent("#1:Patient 1,#2:Patient 2"));
    const client = FakeStompClient.latest();
    act(() => client.connect());
    await waitFor(() => expect(screen.getByTestId("feed")).toHaveTextContent("live"));
    return client;
  }

  it("uses the session token for the socket and applies live events", async () => {
    const onChange = vi.fn();
    const client = await mounted(onChange);
    expect(client.config.connectHeaders).toEqual({ Authorization: "Bearer token-RECEPTIONIST" });

    act(() => client.emit(event({ queueEntryId: "a", entryVersion: 1, status: "CALLED", tokenNumber: 1 })));
    expect(screen.getByTestId("current")).toHaveTextContent("#1 CALLED");
    expect(onChange).toHaveBeenCalledOnce();
  });

  it("ignores duplicate and stale events", async () => {
    const onChange = vi.fn();
    const client = await mounted(onChange);
    const started = event({ queueEntryId: "a", entryVersion: 2, status: "IN_CONSULTATION", tokenNumber: 1 });

    act(() => {
      client.emit(started);
      client.emit(started); // duplicate delivery
      client.emit(event({ queueEntryId: "a", entryVersion: 1, status: "CALLED", tokenNumber: 1 })); // late, stale
    });

    expect(screen.getByTestId("current")).toHaveTextContent("#1 IN_CONSULTATION");
    expect(onChange).toHaveBeenCalledOnce();
  });

  it("reloads the board after reconnecting (missed events are not replayed)", async () => {
    const client = await mounted();
    const boardCalls = () => api.callsTo("GET", "/api/v1/queues/today").length;
    const before = boardCalls();

    act(() => client.drop());
    expect(screen.getByTestId("feed")).toHaveTextContent("offline");

    // While offline, #2 was called on the server.
    serverBoard = board(SHARMA.id, [row({ id: "a", tokenNumber: 1 }), row({ id: "b", tokenNumber: 2, status: "CALLED", version: 1 })]);
    act(() => client.connect());

    await waitFor(() => expect(screen.getByTestId("current")).toHaveTextContent("#2 CALLED"));
    expect(boardCalls()).toBeGreaterThan(before);
  });

  it("fetches the board when an event names an entry it has never seen (for the patient name)", async () => {
    const client = await mounted();
    serverBoard = board(SHARMA.id, [...serverBoard.entries, row({ id: "c", tokenNumber: 3, patientName: "New Walk-in" })]);

    act(() =>
      client.emit(event({ queueEntryId: "c", entryVersion: 0, tokenNumber: 3, status: "WAITING", type: "PATIENT_JOINED_QUEUE", previousStatus: null })),
    );

    await waitFor(() => expect(screen.getByTestId("waiting")).toHaveTextContent("#3:New Walk-in"));
  });
});
