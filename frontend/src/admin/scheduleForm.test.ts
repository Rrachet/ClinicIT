import { describe, expect, it } from "vitest";
import { nextDay, periodLabel, rowsFrom, weekFrom } from "./scheduleForm";

describe("schedule form", () => {
  it("round-trips a saved week through the editable rows", () => {
    const week = [
      { dayOfWeek: "MONDAY" as const, start: "09:00:00", end: "17:00:00", breakStart: "13:00:00", breakEnd: "14:00:00" },
      { dayOfWeek: "SATURDAY" as const, start: "10:00:00", end: "13:00:00", breakStart: null, breakEnd: null },
    ];
    const rows = rowsFrom(week);
    expect(rows.filter((r) => r.works).map((r) => r.day)).toEqual(["MONDAY", "SATURDAY"]);
    expect(weekFrom(rows)).toEqual([
      { dayOfWeek: "MONDAY", start: "09:00", end: "17:00", breakStart: "13:00", breakEnd: "14:00" },
      { dayOfWeek: "SATURDAY", start: "10:00", end: "13:00", breakStart: null, breakEnd: null },
    ]);
  });

  it("ends whole-day leave at midnight after the last day, across months and years", () => {
    expect(nextDay("2026-03-10")).toBe("2026-03-11");
    expect(nextDay("2026-02-28")).toBe("2026-03-01");
    expect(nextDay("2026-12-31")).toBe("2027-01-01");
  });

  it("describes leave periods the way staff think of them", () => {
    expect(periodLabel("2026-03-16T00:00:00", "2026-03-17T00:00:00")).toBe("2026-03-16");
    expect(periodLabel("2026-03-16T00:00:00", "2026-03-19T00:00:00")).toBe("2026-03-16 to 2026-03-18");
    expect(periodLabel("2026-03-16T14:00:00", "2026-03-16T17:00:00")).toBe("2026-03-16 14:00 to 2026-03-16 17:00");
  });
});
