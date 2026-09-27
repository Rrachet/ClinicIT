import { describe, expect, it } from "vitest";
import { board, event, row, SHARMA, MEHTA, CLINIC } from "@/test/fixtures";
import { applyBoard, applyEvent, doctorView, emptyQueue } from "./queueStore";

describe("queueStore", () => {
  const loaded = applyBoard(emptyQueue(), board(SHARMA.id, [row({ id: "a", tokenNumber: 1 }), row({ id: "b", tokenNumber: 2 })]));

  it("builds today's queue from a REST board", () => {
    expect(loaded.day).toBe(CLINIC.today);
    expect(doctorView(loaded, SHARMA.id).waiting.map((i) => i.tokenNumber)).toEqual([1, 2]);
    expect(doctorView(loaded, SHARMA.id).current).toBeNull();
  });

  it("applies a newer event to the entry", () => {
    const { state, outcome } = applyEvent(loaded, event({ queueEntryId: "a", entryVersion: 1, status: "CALLED" }));
    expect(outcome).toBe("applied");
    const view = doctorView(state, SHARMA.id);
    expect(view.current?.tokenNumber).toBe(1);
    expect(view.current?.patientName).toBe("Patient 1"); // kept from the board: events carry no names
    expect(view.waiting.map((i) => i.tokenNumber)).toEqual([2]);
  });

  it("ignores a duplicate delivery of the same eventId", () => {
    const called = event({ queueEntryId: "a", entryVersion: 1, status: "CALLED" });
    const first = applyEvent(loaded, called).state;
    const again = applyEvent(first, called);
    expect(again.outcome).toBe("duplicate");
    expect(again.state).toBe(first);
  });

  it("ignores a stale event whose entryVersion is not newer", () => {
    const started = applyEvent(loaded, event({ queueEntryId: "a", entryVersion: 2, status: "IN_CONSULTATION" })).state;
    // The earlier "called" event arrives late (different thread on the server).
    const late = applyEvent(started, event({ queueEntryId: "a", entryVersion: 1, status: "CALLED" }));
    expect(late.outcome).toBe("stale");
    expect(late.state.items.a.status).toBe("IN_CONSULTATION");

    const sameVersion = applyEvent(started, event({ queueEntryId: "a", entryVersion: 2, status: "CALLED" }));
    expect(sameVersion.outcome).toBe("stale");
  });

  it("a board reload never overwrites a newer event, but fills in names", () => {
    // Event arrives for an entry the board has not shown yet.
    const { state, outcome } = applyEvent(
      loaded,
      event({ queueEntryId: "c", entryVersion: 1, tokenNumber: 3, status: "CALLED", type: "PATIENT_CALLED" }),
    );
    expect(outcome).toBe("unknown-entry");
    expect(state.items.c.patientName).toBeNull();

    // A board read before that change committed (version 0), which knows the name.
    const reloaded = applyBoard(state, board(SHARMA.id, [row({ id: "c", tokenNumber: 3, version: 0, patientName: "Late Board" })]));
    expect(reloaded.items.c.status).toBe("CALLED");
    expect(reloaded.items.c.version).toBe(1);
    expect(reloaded.items.c.patientName).toBe("Late Board");
  });

  it("a newer board replaces older state", () => {
    const reloaded = applyBoard(loaded, board(SHARMA.id, [row({ id: "a", tokenNumber: 1, version: 3, status: "COMPLETED" })]));
    expect(doctorView(reloaded, SHARMA.id).finished.map((i) => i.id)).toEqual(["a"]);
  });

  it("ignores events for another day and starts fresh on a new day's board", () => {
    expect(applyEvent(loaded, event({ queueEntryId: "a", entryVersion: 5, queueDate: "2026-03-09" })).outcome).toBe("other-day");
    const tomorrow = applyBoard(loaded, board(SHARMA.id, [], "2026-03-11"));
    expect(Object.keys(tomorrow.items)).toHaveLength(0);
  });

  it("keeps doctors' queues apart and groups skipped/finished", () => {
    let state = applyBoard(loaded, board(MEHTA.id, [row({ id: "m", tokenNumber: 3 })]));
    state = applyEvent(state, event({ queueEntryId: "b", entryVersion: 1, status: "SKIPPED", tokenNumber: 2 })).state;
    expect(doctorView(state, MEHTA.id).waiting.map((i) => i.id)).toEqual(["m"]);
    expect(doctorView(state, SHARMA.id).skipped.map((i) => i.id)).toEqual(["b"]);
  });
});
