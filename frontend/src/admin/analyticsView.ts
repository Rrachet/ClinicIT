import type { QueueAnalytics, WaitTimes } from "@/api/types";

/** Pure presentation rules for the analytics dashboard (no React, tested directly). */

const DASH = "—";

/** Seconds → "< 1 min", "23 min", "1 h 05 min". Null (no data) → "—", never "0 min". */
export function duration(seconds: number | null): string {
  if (seconds === null) return DASH;
  const minutes = Math.round(Math.abs(seconds) / 60);
  if (minutes === 0) return "< 1 min";
  if (minutes < 60) return `${minutes} min`;
  return `${Math.floor(minutes / 60)} h ${String(minutes % 60).padStart(2, "0")} min`;
}

/** How far behind schedule patients were called on average (negative = early). */
export function delay(seconds: number | null): string {
  if (seconds === null) return DASH;
  if (Math.abs(seconds) < 60) return "on time";
  return `${duration(seconds)} ${seconds > 0 ? "late" : "early"}`;
}

export function percent(rate: number | null): string {
  return rate === null ? DASH : `${Math.round(rate * 100)}%`;
}

export function hourLabel(hour: number): string {
  return `${String(hour).padStart(2, "0")}:00`;
}

export interface HourRow {
  hour: number;
  joined: number;
  completed: number;
  queueLength: number;
  called: number;
  averageWaitSeconds: number | null;
}

/**
 * One row per clinic-local hour, merging queue movement with the wait-time trend.
 * Hours before the day's first activity are dropped so the table starts when the clinic did.
 */
export function hourlyRows(queue: QueueAnalytics, waits: WaitTimes): HourRow[] {
  const waitByHour = new Map(waits.byHour.map((h) => [h.hour, h]));
  const rows = queue.byHour.map((h) => ({
    hour: h.hour,
    joined: h.joined,
    completed: h.completed,
    queueLength: h.queueLength,
    called: waitByHour.get(h.hour)?.calledPatients ?? 0,
    averageWaitSeconds: waitByHour.get(h.hour)?.averageWaitSeconds ?? null,
  }));
  const first = rows.findIndex((r) => r.joined > 0 || r.completed > 0 || r.queueLength > 0 || r.called > 0);
  return first === -1 ? [] : rows.slice(first);
}

/** Bar length as a percentage of the largest value in the column (0 when there is nothing to compare). */
export function barPercent(value: number | null, max: number): number {
  if (value === null || max <= 0) return 0;
  return Math.max(0, Math.min(100, (value / max) * 100));
}
