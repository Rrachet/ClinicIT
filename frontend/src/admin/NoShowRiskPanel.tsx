"use client";

import { useCallback } from "react";

import type { ClinicApi } from "@/api/clinicApi";
import { ErrorBanner } from "@/ui/Feedback";
import { Spinner } from "@/ui/Spinner";
import { useAsync } from "@/ui/useAsync";
import { percent } from "./analyticsView";

/**
 * Does the no-show flag help this clinic? It is replayed over the last 90 days, each day
 * judged with only what was known that morning, and compared with what happened. With too
 * little data the panel says so instead of showing a verdict.
 */
export function NoShowRiskPanel({ api }: { api: ClinicApi }) {
  const evaluation = useAsync(useCallback(() => api.noShowRiskEvaluation(), [api]));
  const e = evaluation.data;

  return (
    <section className="panel" aria-labelledby="noshow-risk-heading">
      <h2 id="noshow-risk-heading">No-show risk flag · last 90 days</h2>
      {!e ? (
        evaluation.error ? <ErrorBanner error={evaluation.error} onRetry={evaluation.reload} /> : <Spinner label="Checking the flag" />
      ) : e.appointments === 0 ? (
        <p className="muted">No booked appointments with an outcome yet.</p>
      ) : (
        <>
          <div className="kpis kpis-compact">
            <div className="kpi">
              <span className="label">Missed overall</span>
              <span className="kpi-value">{percent(e.missRate)}</span>
              <span className="muted small">
                {e.missed} of {e.appointments} booked
              </span>
            </div>
            <div className="kpi">
              <span className="label">Missed when flagged</span>
              <span className="kpi-value">{percent(e.flaggedMissRate)}</span>
              <span className="muted small">{e.flagged} flagged</span>
            </div>
            <div className="kpi">
              <span className="label">Misses caught</span>
              <span className="kpi-value">{percent(e.recall)}</span>
              <span className="muted small">of all misses</span>
            </div>
            <div className="kpi">
              <span className="label">Not judged</span>
              <span className="kpi-value">{e.insufficientHistory}</span>
              <span className="muted small">too little history</span>
            </div>
          </div>
          <p className={e.enoughData ? "small" : "small warn-text"} role="status">
            {e.enoughData
              ? e.lift !== null && e.lift > 1.5
                ? `Flagged patients missed ${e.lift.toFixed(1)}× as often as average here: the flag is a useful prompt for reminder calls.`
                : "Flagged patients did not miss much more often than average here: treat the flag with caution."
              : "Too few flagged appointments or misses to judge the flag yet (it needs at least 20 flagged and 10 missed)."}
          </p>
        </>
      )}
      <p className="muted small">
        The flag uses only each patient&apos;s own attendance here (missed at least 2 of their last appointments, and
        more often than the clinic). It is advice for a reminder call, never a reason to refuse or cancel an appointment.
      </p>
    </section>
  );
}
