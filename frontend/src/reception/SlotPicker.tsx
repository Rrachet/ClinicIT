"use client";

import { useEffect, useState } from "react";
import type { ClinicApi } from "@/api/clinicApi";
import type { Availability } from "@/api/types";

/**
 * A doctor's day for booking. `undefined` while loading; `null` when it could not be
 * loaded (booking then falls back to typing a time, and the backend still checks it).
 */
export function useAvailability(api: ClinicApi, doctorId: string, date: string, enabled = true) {
  const [loaded, setLoaded] = useState<{ key: string; availability: Availability | null } | null>(null);
  const key = `${doctorId}|${date}`;

  useEffect(() => {
    if (!enabled || !doctorId || !date) return;
    let active = true;
    api.availability(doctorId, date).then(
      (availability) => active && setLoaded({ key, availability }),
      () => active && setLoaded({ key, availability: null }),
    );
    return () => {
      active = false;
    };
  }, [api, doctorId, date, key, enabled]);

  return loaded?.key === key ? loaded.availability : undefined;
}

/** Free slot start times ("HH:mm"), or null when the doctor has no schedule (any time goes). */
export function freeTimes(availability: Availability | null | undefined): string[] | null {
  if (!availability?.scheduled) return null;
  return availability.slots.filter((s) => s.available).map((s) => s.start.slice(11, 16));
}

/** The time that will actually be booked: the chosen one if it is free, else the first free slot. */
export function effectiveTime(availability: Availability | null | undefined, chosen: string): string {
  const free = freeTimes(availability);
  if (!free) return chosen;
  return free.includes(chosen) ? chosen : (free[0] ?? "");
}

/**
 * Picks a clinic-local time. With a schedule, only free slots are offered (a slot taken
 * meanwhile is still refused by the backend with a clear message); without one, a plain
 * time input as before. `value` is what {@link effectiveTime} returns.
 */
export function SlotPicker({
  availability,
  value,
  onChange,
  label = "Time",
}: {
  availability: Availability | null | undefined;
  value: string;
  onChange: (time: string) => void;
  label?: string;
}) {
  if (availability === undefined) {
    return (
      <label className="field">
        <span>{label}</span>
        <select disabled aria-busy="true">
          <option>Loading slots…</option>
        </select>
      </label>
    );
  }

  const free = freeTimes(availability);
  if (free) {
    return (
      <label className="field">
        <span>{label}</span>
        {free.length === 0 ? (
          <span className="hint" role="status">
            {availability?.hours ? "No free slots on this day." : "The doctor does not work on this day."}
          </span>
        ) : (
          <select value={value} onChange={(e) => onChange(e.target.value)} required>
            {free.map((time) => (
              <option key={time} value={time}>
                {time}
              </option>
            ))}
          </select>
        )}
      </label>
    );
  }

  return (
    <label className="field">
      <span>{label}</span>
      <input type="time" value={value} onChange={(e) => onChange(e.target.value)} required />
    </label>
  );
}
