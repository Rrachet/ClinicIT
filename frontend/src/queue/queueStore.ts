import type { BoardEntry, QueueBoard, QueueEvent, QueueStatus } from "@/api/types";

/**
 * Client-side mirror of today's queue, built from REST boards and live events.
 * Pure functions, no React: the reconciliation rules from docs/REALTIME.md live here.
 *
 *  1. an event whose eventId was already seen is ignored (delivery is at-least-once)
 *  2. an event (or board row) is applied only if its version is newer than what we hold
 *  3. after (re)connecting, boards are reloaded (events are not replayed); board rows
 *     are subject to rule 2 as well, so a slow reload never overwrites a newer event
 */

export interface QueueItem {
  id: string;
  appointmentId: string;
  doctorId: string;
  tokenNumber: number;
  status: QueueStatus;
  /** Not in events (no patient data on the socket); filled from the REST board. */
  patientName: string | null;
  statusCode: string | null;
  version: number;
}

export interface QueueState {
  day: string | null;
  items: Record<string, QueueItem>;
  /** Recently seen eventIds, oldest first, bounded. */
  seen: string[];
}

export type EventOutcome = "applied" | "unknown-entry" | "duplicate" | "stale" | "other-day";

const SEEN_LIMIT = 2000;

export function emptyQueue(day: string | null = null): QueueState {
  return { day, items: {}, seen: [] };
}

export function applyBoard(state: QueueState, board: QueueBoard): QueueState {
  // A board for a new day starts a fresh queue.
  const base = state.day === board.queueDate ? state : emptyQueue(board.queueDate);
  const items = { ...base.items };
  for (const row of board.entries) {
    const held = items[row.id];
    if (!held || row.version >= held.version) {
      items[row.id] = fromBoardRow(row, board.doctorId);
    } else {
      // We already hold a newer state from an event; just fill in what events lack.
      items[row.id] = { ...held, patientName: held.patientName ?? row.patientName, statusCode: held.statusCode ?? row.statusCode };
    }
  }
  return { ...base, items };
}

export function applyEvent(state: QueueState, event: QueueEvent): { state: QueueState; outcome: EventOutcome } {
  if (state.day !== null && event.queueDate !== state.day) {
    return { state, outcome: "other-day" };
  }
  if (state.seen.includes(event.eventId)) {
    return { state, outcome: "duplicate" };
  }
  const seen = [...state.seen, event.eventId].slice(-SEEN_LIMIT);
  const held = state.items[event.queueEntryId];
  if (held && event.entryVersion <= held.version) {
    return { state: { ...state, seen }, outcome: "stale" };
  }

  const item: QueueItem = {
    id: event.queueEntryId,
    appointmentId: event.appointmentId,
    doctorId: event.doctorId,
    tokenNumber: event.tokenNumber,
    status: event.status,
    patientName: held?.patientName ?? null,
    statusCode: held?.statusCode ?? null,
    version: event.entryVersion,
  };
  return {
    state: { day: state.day ?? event.queueDate, items: { ...state.items, [item.id]: item }, seen },
    outcome: held ? "applied" : "unknown-entry",
  };
}

function fromBoardRow(row: BoardEntry, doctorId: string): QueueItem {
  return {
    id: row.id,
    appointmentId: row.appointmentId,
    doctorId,
    tokenNumber: row.tokenNumber,
    status: row.status,
    patientName: row.patientName,
    statusCode: row.statusCode,
    version: row.version,
  };
}

// ---- Views (how the screens group entries; status meanings come from the backend) ----

export interface DoctorQueueView {
  /** The patient called or in consultation (the backend allows at most one per doctor). */
  current: QueueItem | null;
  /** Waiting, in token order: the order call-next uses. */
  waiting: QueueItem[];
  skipped: QueueItem[];
  finished: QueueItem[];
}

export function doctorView(state: QueueState, doctorId: string): DoctorQueueView {
  const mine = Object.values(state.items)
    .filter((item) => item.doctorId === doctorId)
    .sort((a, b) => a.tokenNumber - b.tokenNumber);
  return {
    current: mine.find((item) => item.status === "CALLED" || item.status === "IN_CONSULTATION") ?? null,
    waiting: mine.filter((item) => item.status === "WAITING"),
    skipped: mine.filter((item) => item.status === "SKIPPED"),
    finished: mine.filter((item) => item.status === "COMPLETED" || item.status === "NO_SHOW"),
  };
}

export function itemForAppointment(state: QueueState, appointmentId: string): QueueItem | undefined {
  return Object.values(state.items).find((item) => item.appointmentId === appointmentId);
}
