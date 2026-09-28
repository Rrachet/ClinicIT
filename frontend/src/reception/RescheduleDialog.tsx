"use client";

import { useEffect, useRef, useState } from "react";
import type { ClinicApi } from "@/api/clinicApi";
import type { Appointment, Clinic } from "@/api/types";
import { Button } from "@/ui/Button";
import { ErrorBanner } from "@/ui/Feedback";
import { SlotPicker, effectiveTime, useAvailability } from "./SlotPicker";

/** Moves a booked or confirmed appointment to another free slot with the same doctor. */
export function RescheduleDialog({
  api,
  clinic,
  appointment,
  onDone,
  onCancel,
}: {
  api: ClinicApi;
  clinic: Clinic;
  appointment: Appointment;
  onDone: (moved: Appointment) => void;
  onCancel: () => void;
}) {
  const current = appointment.scheduledAt.slice(0, 10);
  const [date, setDate] = useState(current < clinic.today ? clinic.today : current);
  const [time, setTime] = useState(appointment.scheduledAt.slice(11, 16));
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<unknown>(null);
  const availability = useAvailability(api, appointment.doctorId, date);
  const chosen = effectiveTime(availability, time);
  const cancelRef = useRef<HTMLButtonElement>(null);

  useEffect(() => {
    cancelRef.current?.focus();
    const onKey = (e: KeyboardEvent) => e.key === "Escape" && onCancel();
    window.addEventListener("keydown", onKey);
    return () => window.removeEventListener("keydown", onKey);
  }, [onCancel]);

  async function save(e: React.FormEvent) {
    e.preventDefault();
    setBusy(true);
    setError(null);
    try {
      onDone(await api.rescheduleAppointment(appointment.id, `${date}T${chosen}:00`));
    } catch (err) {
      setError(err);
      setBusy(false);
    }
  }

  const who = appointment.patientName ?? "this patient";
  return (
    <div className="overlay">
      <form className="dialog stack" role="dialog" aria-modal="true" aria-labelledby="reschedule-title" onSubmit={save}>
        <h2 id="reschedule-title">Reschedule {who}</h2>
        <ErrorBanner error={error} onDismiss={() => setError(null)} />
        <label className="field">
          <span>Date</span>
          <input type="date" value={date} min={clinic.today} onChange={(e) => setDate(e.target.value)} required />
        </label>
        <SlotPicker availability={availability} value={chosen} onChange={setTime} label="New time" />
        <div className="dialog-actions">
          <Button ref={cancelRef} onClick={onCancel} disabled={busy}>
            Keep current time
          </Button>
          <Button type="submit" variant="primary" busy={busy} disabled={availability === undefined || chosen === ""}>
            Move appointment
          </Button>
        </div>
      </form>
    </div>
  );
}
