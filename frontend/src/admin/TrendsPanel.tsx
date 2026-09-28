"use client";

import { useCallback } from "react";

import type { ClinicApi } from "@/api/clinicApi";
import { EmptyState, ErrorBanner } from "@/ui/Feedback";
import { Spinner } from "@/ui/Spinner";
import { useAsync } from "@/ui/useAsync";
import { Bar } from "./Bar";
import { barPercent, duration, percent } from "./analyticsView";

const WEEKDAY = new Intl.DateTimeFormat("en-IN", { weekday: "short", day: "numeric", month: "short", timeZone: "UTC" });

/** "Mon 21 Sep" for a clinic-local date (formatted as UTC so the browser's zone cannot shift it). */
export function dayLabel(isoDate: string): string {
  return WEEKDAY.format(new Date(`${isoDate}T00:00:00Z`));
}

/**
 * The last two weeks, day by day, and each doctor's workload over them. Figures come from the
 * backend (same definitions as the day view); this only lays them out.
 */
export function TrendsPanel({ api, doctorId, doctorName }: { api: ClinicApi; doctorId?: string; doctorName?: string }) {
  const trends = useAsync(useCallback(() => api.analyticsTrends(doctorId), [api, doctorId]));

  if (!trends.data) {
    return (
      <section className="panel" aria-labelledby="trends-heading">
        <h2 id="trends-heading">Last 14 days</h2>
        {trends.error ? <ErrorBanner error={trends.error} onRetry={trends.reload} /> : <Spinner label="Loading trends" />}
      </section>
    );
  }

  const { days, doctors } = trends.data;
  const busiest = Math.max(0, ...days.map((d) => d.scheduled));
  const longestWait = Math.max(0, ...days.map((d) => d.medianWaitSeconds ?? 0));
  const anything = days.some((d) => d.scheduled > 0 || d.checkedIn > 0);

  return (
    <div className="analytics-grid">
      <section className="panel" aria-labelledby="trends-heading">
        <h2 id="trends-heading">Last 14 days{doctorName ? ` · ${doctorName}` : ""}</h2>
        {!anything ? (
          <EmptyState title="No appointments in the last 14 days" />
        ) : (
          <table className="table compact" aria-label="Daily trends">
            <thead>
              <tr>
                <th scope="col">Day</th>
                <th scope="col">Appointments</th>
                <th scope="col" className="num">Completed</th>
                <th scope="col" className="num">Completion</th>
                <th scope="col" className="num">No-shows</th>
                <th scope="col">Median wait</th>
              </tr>
            </thead>
            <tbody>
              {days.map((d) => (
                <tr key={d.date}>
                  <th scope="row">{dayLabel(d.date)}</th>
                  <td>
                    <Bar percent={barPercent(d.scheduled, busiest)} label={String(d.scheduled)} />
                  </td>
                  <td className="num">{d.completed}</td>
                  <td className="num">{percent(d.completionRate)}</td>
                  <td className="num">{percent(d.noShowRate)}</td>
                  <td>
                    <Bar percent={barPercent(d.medianWaitSeconds, longestWait)} label={duration(d.medianWaitSeconds)} tone="warn" />
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        )}
        <p className="muted small">
          Completion and no-shows are shares of the appointments still expected that day (booked minus cancelled).
        </p>
      </section>

      <section className="panel" aria-labelledby="workload-heading">
        <h2 id="workload-heading">Doctor workload · 14 days</h2>
        <table className="table" aria-labelledby="workload-heading">
          <thead>
            <tr>
              <th scope="col">Doctor</th>
              <th scope="col" className="num">Patients seen</th>
              <th scope="col" className="num">Days worked</th>
              <th scope="col" className="num">Per day</th>
              <th scope="col" className="num">Time with patients</th>
            </tr>
          </thead>
          <tbody>
            {doctors.map((d) => (
              <tr key={d.doctorId}>
                <th scope="row">{d.doctorName}</th>
                <td className="num">{d.completed}</td>
                <td className="num">{d.daysWorked}</td>
                <td className="num">{d.completedPerDayWorked === null ? "—" : d.completedPerDayWorked.toFixed(1)}</td>
                <td className="num">{duration(d.consultationSeconds)}</td>
              </tr>
            ))}
          </tbody>
        </table>
      </section>
    </div>
  );
}
