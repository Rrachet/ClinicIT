"use client";

import { useCallback, useEffect, useMemo, useState } from "react";

import { useAuth } from "@/auth/AuthProvider";
import { AppHeader } from "@/ui/AppHeader";
import { Button } from "@/ui/Button";
import { EmptyState, ErrorBanner } from "@/ui/Feedback";
import { FullPageSpinner } from "@/ui/Spinner";
import { longDate } from "@/ui/format";
import { useAsync } from "@/ui/useAsync";
import { barPercent, delay, duration, hourLabel, hourlyRows, percent } from "./analyticsView";
import { Bar } from "./Bar";
import { TrendsPanel } from "./TrendsPanel";

const REFRESH_MS = 60_000;

/**
 * Clinic analytics for admins: how the day is going, per doctor, and how the queue moved.
 * Every figure comes from the backend (computed from the recorded visit history); this
 * screen only formats it.
 */
export function AnalyticsDashboard() {
  const { api } = useAuth();
  const clinicState = useAsync(useCallback(() => api.clinic(), [api]));
  const doctorList = useAsync(useCallback(() => api.doctors(), [api]));
  const clinic = clinicState.data;

  const [pickedDate, setPickedDate] = useState<string | null>(null);
  const [doctorId, setDoctorId] = useState("");
  const date = pickedDate ?? clinic?.today;
  const isToday = date !== undefined && date === clinic?.today;

  const load = useMemo(() => {
    if (!date) return null;
    const doctor = doctorId || undefined;
    return () =>
      Promise.all([
        api.analyticsToday(date, doctor),
        api.analyticsWaitTimes(date, doctor),
        api.analyticsQueue(date, doctor),
        api.analyticsDoctors(date),
      ]).then(([summary, waits, queue, doctors]) => ({ summary, waits, queue, doctors }));
  }, [api, date, doctorId]);
  const figures = useAsync(load);
  const reload = figures.reload;

  useEffect(() => {
    if (!isToday) return;
    const timer = setInterval(reload, REFRESH_MS);
    return () => clearInterval(timer);
  }, [isToday, reload]);

  if (!clinic || !figures.data) {
    const failure = clinicState.error ?? figures.error;
    return failure ? (
      <main className="page">
        <ErrorBanner
          error={failure}
          onRetry={() => {
            clinicState.reload();
            reload();
          }}
        />
      </main>
    ) : (
      <FullPageSpinner label="Loading clinic analytics" />
    );
  }

  const { summary, waits, queue, doctors } = figures.data;
  const hours = hourlyRows(queue, waits);
  const maxCompleted = Math.max(0, ...hours.map((h) => h.completed));
  const maxWait = Math.max(0, ...hours.map((h) => h.averageWaitSeconds ?? 0));
  const maxQueue = Math.max(0, ...hours.map((h) => h.queueLength));
  const selectedDoctor = doctorList.data?.find((d) => d.id === doctorId);

  return (
    <div className="shell">
      <AppHeader title="Clinic analytics" clinicName={clinic.name} />
      <main className="page">
        <div className="toolbar">
          <label className="inline-field">
            Day
            <input
              type="date"
              value={date}
              max={clinic.today}
              onChange={(e) => setPickedDate(e.target.value || null)}
            />
          </label>
          <label className="inline-field">
            Doctor
            <select value={doctorId} onChange={(e) => setDoctorId(e.target.value)}>
              <option value="">All doctors</option>
              {(doctorList.data ?? []).map((d) => (
                <option key={d.id} value={d.id}>
                  {d.displayName}
                </option>
              ))}
            </select>
          </label>
          <Button variant="secondary" size="sm" onClick={reload}>
            Refresh
          </Button>
          <span className="muted small">
            {isToday ? "Updates every minute." : null} Times are clinic time ({summary.timezone}).
          </span>
        </div>
        <ErrorBanner error={figures.error} onRetry={reload} />

        <section className="panel" aria-labelledby="summary-heading">
          <div className="panel-head">
            <h2 id="summary-heading">
              {isToday ? "Today" : longDate(summary.date)}
              {selectedDoctor ? ` · ${selectedDoctor.displayName}` : null}
            </h2>
          </div>
          <div className="kpis">
            <Kpi label="Patients" value={String(summary.patients)} note={`checked in · ${summary.scheduledAppointments} booked`} />
            <Kpi label="Completed" value={String(summary.completedConsultations)} note="consultations" />
            <Kpi label="Average wait" value={duration(summary.averageWaitSeconds)} note="check-in to call" />
            <Kpi label="Median wait" value={duration(summary.medianWaitSeconds)} note="half waited less" />
            <Kpi
              label="No-show rate"
              value={percent(summary.noShowRate)}
              note={`${summary.noShows} of ${summary.scheduledAppointments - summary.cancellations} expected`}
            />
            <Kpi label="Avg consultation" value={duration(summary.averageConsultationSeconds)} note="start to complete" />
          </div>
          <p className="muted small">
            Cancelled: {summary.cancellations} ({percent(summary.cancellationRate)}) · Called versus appointment time:{" "}
            {delay(summary.averageDelaySeconds)}
          </p>
        </section>

        <div className="analytics-grid">
          <section className="panel" aria-labelledby="doctors-heading">
            <h2 id="doctors-heading">Doctors</h2>
            {doctors.doctors.length === 0 ? (
              <EmptyState title="No doctors yet" />
            ) : (
              <table className="table" aria-labelledby="doctors-heading">
                <thead>
                  <tr>
                    <th scope="col">Doctor</th>
                    <th scope="col" className="num">Patients seen</th>
                    <th scope="col" className="num">Avg wait</th>
                    <th scope="col" className="num">Avg consultation</th>
                    <th scope="col">Busy while seeing patients</th>
                    <th scope="col">Of scheduled hours</th>
                  </tr>
                </thead>
                <tbody>
                  {doctors.doctors.map((d) => (
                    <tr key={d.doctorId}>
                      <th scope="row">{d.doctorName}</th>
                      <td className="num">{d.patientsHandled}</td>
                      <td className="num">{duration(d.averageWaitSeconds)}</td>
                      <td className="num">{duration(d.averageConsultationSeconds)}</td>
                      <td>
                        <span className="bar-cell">
                          <span className="bar" aria-hidden>
                            <span className="bar-fill" style={{ width: `${barPercent(d.utilization, 1)}%` }} />
                          </span>
                          {percent(d.utilization)}
                        </span>
                      </td>
                      <td>
                        {d.scheduledMinutes == null ? (
                          <span className="muted">No schedule</span>
                        ) : (
                          <span className="bar-cell">
                            <span className="bar" aria-hidden>
                              <span className="bar-fill" style={{ width: `${barPercent(d.scheduledUtilization ?? null, 1)}%` }} />
                            </span>
                            {percent(d.scheduledUtilization ?? null)} of {duration(d.scheduledMinutes * 60)}
                          </span>
                        )}
                      </td>
                    </tr>
                  ))}
                </tbody>
              </table>
            )}
            <p className="muted small">
              Busy while seeing patients: time in consultation ÷ time from the doctor&apos;s first call to their last
              completed patient. Of scheduled hours: time in consultation ÷ the doctor&apos;s working hours that day,
              less the break and leave (only for doctors with a schedule).
            </p>
          </section>

          <section className="panel" aria-labelledby="queue-heading">
            <div className="panel-head">
              <h2 id="queue-heading">Queue</h2>
              <span className="queue-now">
                <span className="label">{isToday ? "Waiting now" : "Left waiting"}</span>
                <span className="token token-md" data-testid="current-queue-length">
                  {summary.currentQueueLength}
                </span>
              </span>
            </div>
            {hours.length === 0 ? (
              <EmptyState title="No queue activity" hint={isToday ? "Patients appear here once they check in." : undefined} />
            ) : (
              <table className="table compact" aria-label="Queue by hour">
                <thead>
                  <tr>
                    <th scope="col">Hour</th>
                    <th scope="col" className="num">Checked in</th>
                    <th scope="col">Completed</th>
                    <th scope="col">Waiting at end</th>
                    <th scope="col">Avg wait of those called</th>
                  </tr>
                </thead>
                <tbody>
                  {hours.map((h) => (
                    <tr key={h.hour}>
                      <th scope="row" className="mono">{hourLabel(h.hour)}</th>
                      <td className="num">{h.joined}</td>
                      <td>
                        <Bar percent={barPercent(h.completed, maxCompleted)} label={String(h.completed)} />
                      </td>
                      <td>
                        <Bar percent={barPercent(h.queueLength, maxQueue)} label={String(h.queueLength)} tone="muted" />
                      </td>
                      <td>
                        <Bar
                          percent={barPercent(h.averageWaitSeconds, maxWait)}
                          label={h.called ? duration(h.averageWaitSeconds) : "—"}
                          tone="warn"
                        />
                      </td>
                    </tr>
                  ))}
                </tbody>
              </table>
            )}
          </section>
        </div>

        {/* Keyed by doctor: its own loading and errors, so a slow range never holds up the day's figures. */}
        <TrendsPanel key={doctorId || "all"} api={api} doctorId={doctorId || undefined} doctorName={selectedDoctor?.displayName} />
      </main>
    </div>
  );
}

function Kpi({ label, value, note }: { label: string; value: string; note: string }) {
  return (
    <div className="kpi">
      <span className="label">{label}</span>
      <span className="kpi-value">{value}</span>
      <span className="muted small">{note}</span>
    </div>
  );
}
