"use client";

import { useCallback, useEffect, useMemo, useRef, useState } from "react";

import { useAuth, useSession } from "@/auth/AuthProvider";
import { doctorView, type QueueItem } from "@/queue/queueStore";
import { useLiveQueue } from "@/queue/useLiveQueue";
import { AppHeader } from "@/ui/AppHeader";
import { Button } from "@/ui/Button";
import { ConfirmDialog } from "@/ui/ConfirmDialog";
import { EmptyState, ErrorBanner } from "@/ui/Feedback";
import { FullPageSpinner } from "@/ui/Spinner";
import { StatusBadge } from "@/ui/StatusBadge";
import { useAsync } from "@/ui/useAsync";
import { statusLabel, timeOf } from "@/ui/format";

/** The doctor's own queue: who is in the room, who is next, and one obvious next step. */
export function DoctorConsole() {
  const { api } = useAuth();
  const session = useSession();
  const doctorId = session.user.doctorProfileId!;
  const [error, setError] = useState<unknown>(null);
  const [busy, setBusy] = useState<string | null>(null);
  const [confirmSkip, setConfirmSkip] = useState<QueueItem | null>(null);

  const clinicState = useAsync(useCallback(() => api.clinic(), [api]));
  const clinic = clinicState.data ?? null;
  const today = clinic?.today;
  // The backend returns only this doctor's appointments to a doctor.
  const appointmentList = useAsync(useCallback(() => (today ? api.appointments(today) : Promise.resolve([])), [api, today]));
  const appointments = appointmentList.data ?? [];
  const reloadAppointments = appointmentList.reload;

  const refreshTimer = useRef<ReturnType<typeof setTimeout> | undefined>(undefined);
  const onChange = useCallback(() => {
    clearTimeout(refreshTimer.current);
    refreshTimer.current = setTimeout(reloadAppointments, 300);
  }, [reloadAppointments]);
  useEffect(() => () => clearTimeout(refreshTimer.current), []);

  const doctorIds = useMemo(() => [doctorId], [doctorId]);
  const live = useLiveQueue({
    doctorIds,
    destination: `/topic/clinic/${session.user.clinicId}/doctor/${doctorId}/queue`,
    onChange,
  });
  const view = doctorView(live.queue, doctorId);
  const current = view.current;
  const currentAppointment = current ? appointments.find((a) => a.id === current.appointmentId) : undefined;

  async function act(key: string, call: () => Promise<unknown>) {
    setBusy(key);
    setError(null);
    try {
      await call();
    } catch (e) {
      setError(e);
    } finally {
      setBusy(null);
    }
  }

  if (!clinic || !live.loaded) {
    const failure = live.error ?? clinicState.error;
    return failure ? (
      <main className="page">
        <ErrorBanner
          error={failure}
          onRetry={() => {
            clinicState.reload();
            void live.reload();
          }}
        />
      </main>
    ) : (
      <FullPageSpinner label="Loading your queue" />
    );
  }

  return (
    <div className="shell">
      <AppHeader title="My Queue" clinicName={clinic.name} day={clinic.today} feed={live.feedStatus} />
      <main className="page doctor-grid">
        <ErrorBanner error={error} onDismiss={() => setError(null)} />

        <section className="panel current-panel" aria-labelledby="current-title">
          <h2 id="current-title">Current patient</h2>
          {current ? (
            <div className="current">
              <span className="token token-hero" data-testid="doctor-current-token">
                #{current.tokenNumber}
              </span>
              <div className="current-detail">
                <p className="current-name">{current.patientName ?? "…"}</p>
                <StatusBadge status={current.status} />
                {currentAppointment ? (
                  <dl className="facts">
                    <dt>Appointment</dt>
                    <dd>{timeOf(currentAppointment.scheduledAt)}</dd>
                    {currentAppointment.reasonSummary ? (
                      <>
                        <dt>Reason</dt>
                        <dd>{currentAppointment.reasonSummary}</dd>
                      </>
                    ) : null}
                  </dl>
                ) : null}
              </div>
              <div className="current-actions">
                {current.status === "CALLED" ? (
                  <>
                    <Button
                      variant="primary"
                      size="lg"
                      busy={busy === "start"}
                      onClick={() => act("start", () => api.startConsultation(current.id))}
                    >
                      Start consultation
                    </Button>
                    <Button onClick={() => setConfirmSkip(current)}>Patient not here</Button>
                  </>
                ) : (
                  <Button
                    variant="primary"
                    size="lg"
                    busy={busy === "complete"}
                    onClick={() => act("complete", () => api.completeConsultation(current.id))}
                  >
                    Complete consultation
                  </Button>
                )}
              </div>
            </div>
          ) : (
            <div className="current current-empty">
              <p className="muted">No patient with you.</p>
              <Button
                variant="primary"
                size="lg"
                busy={busy === "call"}
                disabled={view.waiting.length === 0}
                onClick={() => act("call", () => api.callNext())}
              >
                {view.waiting.length ? `Call next · #${view.waiting[0].tokenNumber}` : "No one waiting"}
              </Button>
            </div>
          )}
        </section>

        <section className="panel" aria-labelledby="upcoming-title">
          <h2 id="upcoming-title">
            Up next <span className="muted">({view.waiting.length})</span>
          </h2>
          {view.waiting.length === 0 ? (
            <EmptyState title="Queue is empty" hint="Patients appear here as reception checks them in." />
          ) : (
            <ol className="queue-list">
              {view.waiting.map((item, index) => (
                <li key={item.id}>
                  <span className="token token-md">#{item.tokenNumber}</span>
                  <span>{item.patientName ?? "…"}</span>
                  <span className="muted">{index === 0 ? "next" : `${index} ahead`}</span>
                </li>
              ))}
            </ol>
          )}
          {view.skipped.length ? (
            <p className="muted">Skipped: {view.skipped.map((i) => `#${i.tokenNumber}`).join(", ")}</p>
          ) : null}
        </section>

        <section className="panel" aria-labelledby="today-title">
          <h2 id="today-title">Today&apos;s appointments</h2>
          {appointments.length === 0 ? (
            <EmptyState title="No appointments today" />
          ) : (
            <table className="table compact">
              <tbody>
                {appointments.map((a) => (
                  <tr key={a.id}>
                    <td className="mono">{timeOf(a.scheduledAt)}</td>
                    <td>{a.patientName}</td>
                    <td>
                      <StatusBadge status={a.status} />
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
          )}
          <p className="muted small">
            {view.finished.length} seen or closed today · current: {current ? statusLabel(current.status) : "none"}
          </p>
        </section>
      </main>

      <ConfirmDialog
        request={
          confirmSkip
            ? {
                title: `Skip token #${confirmSkip.tokenNumber}?`,
                message: "Use this when the patient did not come in. Reception can put them back in the queue.",
                confirmLabel: "Skip patient",
                danger: true,
              }
            : null
        }
        busy={busy === "skip"}
        onCancel={() => setConfirmSkip(null)}
        onConfirm={() => {
          const target = confirmSkip!;
          void act("skip", () => api.skip(target.id)).then(() => setConfirmSkip(null));
        }}
      />
    </div>
  );
}
