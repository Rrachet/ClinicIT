import { describe, expect, it } from "vitest";
import type { QueueAnalytics, WaitTimes } from "@/api/types";
import { barPercent, delay, duration, hourLabel, hourlyRows, percent } from "./analyticsView";

describe("analytics formatting", () => {
  it("shows durations in minutes and hours, and no data as a dash rather than zero", () => {
    expect(duration(null)).toBe("—");
    expect(duration(0)).toBe("< 1 min");
    expect(duration(29)).toBe("< 1 min");
    expect(duration(2100)).toBe("35 min");
    expect(duration(3900)).toBe("1 h 05 min");
  });

  it("describes schedule delay as late, early or on time", () => {
    expect(delay(null)).toBe("—");
    expect(delay(30)).toBe("on time");
    expect(delay(2325)).toBe("39 min late");
    expect(delay(-600)).toBe("10 min early");
  });

  it("rounds rates to whole percentages", () => {
    expect(percent(null)).toBe("—");
    expect(percent(1 / 7)).toBe("14%");
    expect(percent(0)).toBe("0%");
  });

  it("sizes bars against the column maximum", () => {
    expect(barPercent(3, 6)).toBe(50);
    expect(barPercent(null, 6)).toBe(0);
    expect(barPercent(2, 0)).toBe(0);
    expect(barPercent(1.2, 1)).toBe(100);
    expect(hourLabel(9)).toBe("09:00");
  });
});

describe("hourlyRows", () => {
  const queue = (byHour: QueueAnalytics["byHour"]): QueueAnalytics => ({ date: "2026-03-10", doctorId: null, currentQueueLength: 1, byHour });
  const waits = (byHour: WaitTimes["byHour"]): WaitTimes => ({
    date: "2026-03-10", doctorId: null, calledPatients: 0, averageWaitSeconds: null, medianWaitSeconds: null,
    p90WaitSeconds: null, maxWaitSeconds: null, byHour,
  });

  it("starts at the first hour with activity and merges the wait trend", () => {
    const rows = hourlyRows(
      queue([
        { hour: 7, joined: 0, completed: 0, queueLength: 0 },
        { hour: 8, joined: 0, completed: 0, queueLength: 0 },
        { hour: 9, joined: 3, completed: 1, queueLength: 1 },
        { hour: 10, joined: 2, completed: 3, queueLength: 1 },
        { hour: 11, joined: 0, completed: 0, queueLength: 1 },
      ]),
      waits([{ hour: 10, calledPatients: 2, averageWaitSeconds: 2550, medianWaitSeconds: 2550 }]),
    );
    expect(rows.map((r) => r.hour)).toEqual([9, 10, 11]);
    expect(rows[1]).toEqual({ hour: 10, joined: 2, completed: 3, queueLength: 1, called: 2, averageWaitSeconds: 2550 });
    expect(rows[0].averageWaitSeconds).toBeNull();
  });

  it("is empty for a day without activity", () => {
    expect(hourlyRows(queue([{ hour: 9, joined: 0, completed: 0, queueLength: 0 }]), waits([]))).toEqual([]);
  });
});
