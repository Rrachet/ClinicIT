import { describe, expect, it } from "vitest";
import type { WaitEstimate } from "@/api/types";
import { board, event, row, SHARMA } from "@/test/fixtures";
import { applyBoard, applyEvent, emptyQueue } from "./queueStore";
import { approxMinutes, describeSource, minuteRange, queueSignature } from "./waitEstimate";

const estimate = (overrides: Partial<WaitEstimate> = {}): WaitEstimate => ({
  queueEntryId: "qa", tokenNumber: 7, estimatedWaitMinutes: 23, lowerBoundMinutes: 17, upperBoundMinutes: 31,
  source: "MODEL", modelVersion: "wait-random-forest-abc", fallbackReason: null, ...overrides,
});

describe("wait estimate wording", () => {
  it("is always approximate, and never zero", () => {
    expect(approxMinutes(23.4)).toBe("~23 min");
    expect(approxMinutes(0)).toBe("~1 min");
  });

  it("shows a range, or 'under 5 min' when it is that short", () => {
    expect(minuteRange(17, 31)).toBe("17–31 min");
    expect(minuteRange(0, 5)).toBe("under 5 min");
    expect(minuteRange(12, 12)).toBe("~12 min");
  });

  it("says where the estimate came from", () => {
    expect(describeSource(estimate())).toContain("wait-random-forest-abc");
    expect(describeSource(estimate({ source: "BASELINE", modelVersion: "baseline-v1" }))).toContain("Rough estimate");
  });
});

describe("queueSignature", () => {
  const loaded = applyBoard(emptyQueue(), board(SHARMA.id, [
    row({ id: "qa", tokenNumber: 7 }),
    row({ id: "qb", tokenNumber: 8 }),
  ]));

  it("changes when someone is called or joins", () => {
    const before = queueSignature(loaded, SHARMA.id);
    const called = applyEvent(loaded, event({ queueEntryId: "qa", appointmentId: "appt-qa", tokenNumber: 7, status: "CALLED", entryVersion: 1 })).state;
    expect(queueSignature(called, SHARMA.id)).not.toBe(before);
    const joined = applyBoard(loaded, board(SHARMA.id, [row({ id: "qa", tokenNumber: 7 }), row({ id: "qb", tokenNumber: 8 }), row({ id: "qc", tokenNumber: 9 })]));
    expect(queueSignature(joined, SHARMA.id)).not.toBe(before);
  });

  it("does not change for another doctor's queue", () => {
    expect(queueSignature(loaded, "someone-else")).toBe("-|");
  });
});
