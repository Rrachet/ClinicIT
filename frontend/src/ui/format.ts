import type { AppointmentStatus, QueueStatus } from "@/api/types";

/** "2026-03-10T16:00:00" (clinic-local, no offset) → "16:00", without any timezone maths. */
export function timeOf(localDateTime: string): string {
  return localDateTime.slice(11, 16);
}

/** Current wall-clock time in the clinic's timezone, as "YYYY-MM-DDTHH:mm". */
export function clinicNow(timezone: string, now: Date = new Date()): string {
  const parts = Object.fromEntries(
    new Intl.DateTimeFormat("en-CA", {
      timeZone: timezone,
      year: "numeric",
      month: "2-digit",
      day: "2-digit",
      hour: "2-digit",
      minute: "2-digit",
      hourCycle: "h23",
    })
      .formatToParts(now)
      .map((p) => [p.type, p.value]),
  );
  return `${parts.year}-${parts.month}-${parts.day}T${parts.hour}:${parts.minute}`;
}

export function longDate(isoDate: string): string {
  const [y, m, d] = isoDate.split("-").map(Number);
  return new Date(Date.UTC(y, m - 1, d)).toLocaleDateString("en-GB", {
    weekday: "long",
    day: "numeric",
    month: "long",
    timeZone: "UTC",
  });
}

const LABELS: Record<AppointmentStatus, string> = {
  BOOKED: "Booked",
  CONFIRMED: "Confirmed",
  ARRIVED: "Arrived",
  WAITING: "Waiting",
  CALLED: "Called",
  IN_CONSULTATION: "In consultation",
  COMPLETED: "Completed",
  CANCELLED: "Cancelled",
  NO_SHOW: "No-show",
  SKIPPED: "Skipped",
};

export function statusLabel(status: AppointmentStatus | QueueStatus): string {
  return LABELS[status];
}
