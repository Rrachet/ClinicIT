"use client";

import { useCallback, useId, useState } from "react";

import type { ClinicApi } from "@/api/clinicApi";
import type { DoctorSchedule } from "@/api/types";
import { useAuth } from "@/auth/AuthProvider";
import { AppHeader } from "@/ui/AppHeader";
import { Button } from "@/ui/Button";
import { EmptyState, ErrorBanner, Notice } from "@/ui/Feedback";
import { FullPageSpinner } from "@/ui/Spinner";
import { useAsync } from "@/ui/useAsync";
import { DAYS, nextDay, periodLabel, rowsFrom, weekFrom, type DayRow } from "./scheduleForm";

const LENGTHS = [5, 10, 15, 20, 30, 45, 60];

/**
 * Admins set each doctor's weekly hours, lunch break, appointment length and leave. Booking
 * follows these rules once a doctor has hours (docs/SCHEDULING.md); a doctor without any
 * can be booked at any time.
 */
export function ScheduleEditor() {
  const { api } = useAuth();
  const setup = useAsync(useCallback(() => Promise.all([api.clinic(), api.doctors()]), [api]));
  const [chosenDoctor, setChosenDoctor] = useState("");

  if (!setup.data) {
    return setup.error ? (
      <main className="page">
        <ErrorBanner error={setup.error} onRetry={setup.reload} />
      </main>
    ) : (
      <FullPageSpinner label="Loading doctors" />
    );
  }
  const [clinic, doctors] = setup.data;
  const doctorId = chosenDoctor || doctors[0]?.id || "";

  return (
    <>
      <AppHeader title="Doctor schedules" clinicName={clinic.name} />
      <main className="page stack">
        {doctors.length === 0 ? (
          <EmptyState title="No doctors yet" hint="Add a doctor first, then set their hours here." />
        ) : (
          <>
            <label className="field" style={{ maxWidth: "20rem" }}>
              <span>Doctor</span>
              <select value={doctorId} onChange={(e) => setChosenDoctor(e.target.value)}>
                {doctors.map((d) => (
                  <option key={d.id} value={d.id}>
                    {d.displayName}
                  </option>
                ))}
              </select>
            </label>
            {/* Keyed by doctor so each doctor's form starts from their saved schedule. */}
            <DoctorScheduleForm key={doctorId} api={api} doctorId={doctorId} today={clinic.today} />
          </>
        )}
      </main>
    </>
  );
}

function DoctorScheduleForm({ api, doctorId, today }: { api: ClinicApi; doctorId: string; today: string }) {
  const saved = useAsync(useCallback(() => api.schedule(doctorId), [api, doctorId]));
  if (!saved.data) {
    return saved.error ? <ErrorBanner error={saved.error} onRetry={saved.reload} /> : <FullPageSpinner label="Loading schedule" />;
  }
  return (
    <>
      <WeekForm api={api} schedule={saved.data} onSaved={saved.reload} />
      <TimeOffPanel api={api} schedule={saved.data} today={today} onChanged={saved.reload} />
    </>
  );
}

function WeekForm({ api, schedule, onSaved }: { api: ClinicApi; schedule: DoctorSchedule; onSaved: () => void }) {
  const ids = useId();
  const [rows, setRows] = useState<DayRow[]>(() => rowsFrom(schedule.weeklyHours));
  const [minutes, setMinutes] = useState(schedule.appointmentMinutes);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<unknown>(null);
  const [done, setDone] = useState<string | null>(null);

  const update = (index: number, change: Partial<DayRow>) =>
    setRows((current) => current.map((row, i) => (i === index ? { ...row, ...change } : row)));

  async function save(e: React.FormEvent) {
    e.preventDefault();
    setBusy(true);
    setError(null);
    setDone(null);
    try {
      const week = weekFrom(rows);
      await api.updateSchedule(schedule.doctorId, { appointmentMinutes: minutes, weeklyHours: week });
      setDone(week.length === 0 ? "Schedule removed: this doctor can be booked at any time." : "Schedule saved.");
      onSaved();
    } catch (err) {
      setError(err);
    } finally {
      setBusy(false);
    }
  }

  return (
    <form className="panel" aria-labelledby={`${ids}-title`} onSubmit={save}>
      <div className="panel-head">
        <h2 id={`${ids}-title`}>Weekly hours</h2>
        <label className="field">
          <span>Appointment length</span>
          <select value={minutes} onChange={(e) => setMinutes(Number(e.target.value))}>
            {LENGTHS.map((m) => (
              <option key={m} value={m}>
                {m} minutes
              </option>
            ))}
          </select>
        </label>
      </div>
      <ErrorBanner error={error} onDismiss={() => setError(null)} />
      {done ? <Notice>{done}</Notice> : null}
      <table className="table compact">
        <thead>
          <tr>
            <th scope="col">Day</th>
            <th scope="col">Works</th>
            <th scope="col">From</th>
            <th scope="col">To</th>
            <th scope="col">Break from</th>
            <th scope="col">Break to</th>
          </tr>
        </thead>
        <tbody>
          {rows.map((row, i) => {
            const label = DAYS[i].label;
            return (
              <tr key={row.day}>
                <th scope="row">{label}</th>
                <td>
                  <input type="checkbox" checked={row.works} aria-label={`${label}: works`} onChange={(e) => update(i, { works: e.target.checked })} />
                </td>
                <td>
                  <input type="time" value={row.start} disabled={!row.works} aria-label={`${label}: from`} onChange={(e) => update(i, { start: e.target.value })} required={row.works} />
                </td>
                <td>
                  <input type="time" value={row.end} disabled={!row.works} aria-label={`${label}: to`} onChange={(e) => update(i, { end: e.target.value })} required={row.works} />
                </td>
                <td>
                  <input type="time" value={row.breakStart} disabled={!row.works} aria-label={`${label}: break from`} onChange={(e) => update(i, { breakStart: e.target.value })} />
                </td>
                <td>
                  <input type="time" value={row.breakEnd} disabled={!row.works} aria-label={`${label}: break to`} onChange={(e) => update(i, { breakEnd: e.target.value })} />
                </td>
              </tr>
            );
          })}
        </tbody>
      </table>
      <p className="hint">
        Days that are not ticked are days off. Walk-ins are still taken during breaks while the doctor works that day.
      </p>
      <div className="row">
        <Button type="submit" variant="primary" busy={busy}>
          Save weekly hours
        </Button>
      </div>
    </form>
  );
}

function TimeOffPanel({
  api,
  schedule,
  today,
  onChanged,
}: {
  api: ClinicApi;
  schedule: DoctorSchedule;
  today: string;
  onChanged: () => void;
}) {
  const ids = useId();
  const [from, setFrom] = useState(today);
  const [to, setTo] = useState(today);
  const [reason, setReason] = useState("");
  const [busy, setBusy] = useState<string | null>(null);
  const [error, setError] = useState<unknown>(null);
  const [done, setDone] = useState<string | null>(null);

  async function add(e: React.FormEvent) {
    e.preventDefault();
    setBusy("add");
    setError(null);
    setDone(null);
    try {
      const result = await api.addTimeOff(schedule.doctorId, {
        startsAt: `${from}T00:00:00`,
        endsAt: `${nextDay(to < from ? from : to)}T00:00:00`,
        reason: reason.trim() || undefined,
      });
      setDone(
        result.bookedAppointments > 0
          ? `Leave added. ${result.bookedAppointments} booked appointment(s) fall in it: reschedule or cancel them from reception.`
          : "Leave added.",
      );
      setReason("");
      onChanged();
    } catch (err) {
      setError(err);
    } finally {
      setBusy(null);
    }
  }

  async function remove(id: string) {
    setBusy(id);
    setError(null);
    setDone(null);
    try {
      await api.removeTimeOff(schedule.doctorId, id);
      onChanged();
    } catch (err) {
      setError(err);
    } finally {
      setBusy(null);
    }
  }

  return (
    <section className="panel" aria-labelledby={`${ids}-title`}>
      <h2 id={`${ids}-title`}>Leave and time off</h2>
      <ErrorBanner error={error} onDismiss={() => setError(null)} />
      {done ? <Notice>{done}</Notice> : null}
      {schedule.timeOff.length === 0 ? (
        <p className="muted">No leave planned.</p>
      ) : (
        <ul className="stack" aria-label="Planned leave">
          {schedule.timeOff.map((off) => (
            <li key={off.id} className="row" style={{ alignItems: "center", justifyContent: "space-between" }}>
              <span>
                <strong>{periodLabel(off.startsAt, off.endsAt)}</strong>
                {off.reason ? <span className="muted"> · {off.reason}</span> : null}
              </span>
              <Button size="sm" variant="ghost" busy={busy === off.id} onClick={() => void remove(off.id)} aria-label={`Remove leave ${periodLabel(off.startsAt, off.endsAt)}`}>
                Remove
              </Button>
            </li>
          ))}
        </ul>
      )}
      <form className="row" onSubmit={add} style={{ alignItems: "flex-end" }}>
        <label className="field">
          <span>First day</span>
          <input type="date" value={from} min={today} onChange={(e) => setFrom(e.target.value)} required />
        </label>
        <label className="field">
          <span>Last day</span>
          <input type="date" value={to} min={from} onChange={(e) => setTo(e.target.value)} required />
        </label>
        <label className="field">
          <span>Note for staff (optional)</span>
          <input value={reason} onChange={(e) => setReason(e.target.value)} maxLength={200} />
        </label>
        <Button type="submit" variant="primary" busy={busy === "add"}>
          Add leave
        </Button>
      </form>
    </section>
  );
}
