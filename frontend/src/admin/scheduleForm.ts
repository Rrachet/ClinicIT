import type { DayOfWeek, WorkingDay } from "@/api/types";

export const DAYS: { day: DayOfWeek; label: string }[] = [
  { day: "MONDAY", label: "Monday" },
  { day: "TUESDAY", label: "Tuesday" },
  { day: "WEDNESDAY", label: "Wednesday" },
  { day: "THURSDAY", label: "Thursday" },
  { day: "FRIDAY", label: "Friday" },
  { day: "SATURDAY", label: "Saturday" },
  { day: "SUNDAY", label: "Sunday" },
];

/** One editable row: times as "HH:mm", empty break fields mean no break. */
export interface DayRow {
  day: DayOfWeek;
  works: boolean;
  start: string;
  end: string;
  breakStart: string;
  breakEnd: string;
}

const hhmm = (time: string | null) => (time ? time.slice(0, 5) : "");

export function rowsFrom(weeklyHours: WorkingDay[]): DayRow[] {
  return DAYS.map(({ day }) => {
    const hours = weeklyHours.find((h) => h.dayOfWeek === day);
    return hours
      ? { day, works: true, start: hhmm(hours.start), end: hhmm(hours.end), breakStart: hhmm(hours.breakStart), breakEnd: hhmm(hours.breakEnd) }
      : { day, works: false, start: "09:00", end: "17:00", breakStart: "", breakEnd: "" };
  });
}

/** The week to send; the backend validates every rule and explains any mistake. */
export function weekFrom(rows: DayRow[]): WorkingDay[] {
  return rows
    .filter((row) => row.works)
    .map((row) => ({
      dayOfWeek: row.day,
      start: row.start,
      end: row.end,
      breakStart: row.breakStart || null,
      breakEnd: row.breakEnd || null,
    }));
}

/** The day after a "YYYY-MM-DD" date (time off ends at 00:00 after its last day). */
export function nextDay(date: string): string {
  const d = new Date(`${date}T00:00:00Z`);
  d.setUTCDate(d.getUTCDate() + 1);
  return d.toISOString().slice(0, 10);
}

/** "12 Mar 09:00 – 13 Mar 00:00" style range for a time-off period. */
export function periodLabel(startsAt: string, endsAt: string): string {
  const wholeDays = startsAt.endsWith("T00:00:00") && endsAt.endsWith("T00:00:00");
  if (wholeDays) {
    const lastDay = new Date(`${endsAt.slice(0, 10)}T00:00:00Z`);
    lastDay.setUTCDate(lastDay.getUTCDate() - 1);
    const last = lastDay.toISOString().slice(0, 10);
    return last === startsAt.slice(0, 10) ? startsAt.slice(0, 10) : `${startsAt.slice(0, 10)} to ${last}`;
  }
  return `${startsAt.slice(0, 16).replace("T", " ")} to ${endsAt.slice(0, 16).replace("T", " ")}`;
}
