import type { WaitEstimate } from "@/api/types";
import { doctorView, type QueueState } from "./queueStore";

/** Pure rules for showing wait estimates (no React, tested directly). */

/** "~23 min". Never "~0 min": the next patient still waits for the one in the room. */
export function approxMinutes(minutes: number): string {
  return `~${Math.max(1, Math.round(minutes))} min`;
}

/** "17–31 min", or "under 5 min" when the whole range is that short. */
export function minuteRange(lower: number, upper: number): string {
  if (upper <= 5) return "under 5 min";
  if (lower >= upper) return approxMinutes(upper);
  return `${Math.max(0, Math.round(lower))}–${Math.round(upper)} min`;
}

export function describeSource(estimate: WaitEstimate): string {
  return estimate.source === "MODEL"
    ? `Estimated by ${estimate.modelVersion}; range ${minuteRange(estimate.lowerBoundMinutes, estimate.upperBoundMinutes)}`
    : `Rough estimate (patients ahead × average consultation); range ${minuteRange(estimate.lowerBoundMinutes, estimate.upperBoundMinutes)}`;
}

/**
 * Changes only when the doctor's queue materially changes (someone joins, is called, leaves
 * or comes back), not on every event, so estimates are refetched only when they could differ.
 */
export function queueSignature(queue: QueueState, doctorId: string): string {
  const view = doctorView(queue, doctorId);
  const current = view.current ? `${view.current.id}:${view.current.status}` : "-";
  return `${current}|${view.waiting.map((item) => item.id).join(",")}`;
}
