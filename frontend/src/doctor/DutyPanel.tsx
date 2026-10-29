"use client";

import { useCallback, useState } from "react";
import type { ClinicApi } from "@/api/clinicApi";
import type { Doctor } from "@/api/types";
import { Button } from "@/ui/Button";
import { ErrorBanner } from "@/ui/Feedback";
import { useAsync } from "@/ui/useAsync";

/**
 * "Go live": tells self check-in that this doctor is seeing patients, so patients who scan
 * the clinic's QR code can be queued with them. Offline stops new patients; anyone already
 * queued stays. The room is what a called patient is told to go to.
 */
export function DutyPanel({ api, doctorId }: { api: ClinicApi; doctorId: string }) {
  const doctors = useAsync(useCallback(() => api.doctors(), [api]));
  // The server's answer to the last change wins over the list loaded at start.
  const [updated, setDoctor] = useState<Doctor | null>(null);
  const doctor = updated ?? doctors.data?.find((d) => d.id === doctorId) ?? null;
  const [roomDraft, setRoom] = useState<string | null>(null);
  const room = roomDraft ?? doctor?.room ?? "";
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<unknown>(null);

  async function setLive(live: boolean) {
    setBusy(true);
    setError(null);
    try {
      setDoctor(await api.setDuty(doctorId, { live, room: room.trim() }));
      setRoom(null);
    } catch (e) {
      setError(e);
    } finally {
      setBusy(false);
    }
  }

  if (doctors.error) return <ErrorBanner error={doctors.error} onRetry={doctors.reload} />;
  if (!doctor) return null;
  const live = doctor.live === true;
  return (
    <section className={`panel duty-panel${live ? " duty-live" : ""}`} aria-labelledby="duty-title">
      <div className="duty-state">
        <h2 id="duty-title">
          <span className={`live ${live ? "live-live" : "live-offline"}`}>
            <span className="live-dot" aria-hidden />
            {live ? "You're live" : "You're offline"}
          </span>
        </h2>
        <p className="muted small">
          {live
            ? "Patients who check in with the clinic's QR code can be sent to you."
            : "Go live when you are ready to see patients. Until then, no new patients are sent to you."}
        </p>
      </div>
      <ErrorBanner error={error} onDismiss={() => setError(null)} />
      <div className="duty-actions">
        <label className="inline-field">
          Room
          <input value={room} onChange={(e) => setRoom(e.target.value)} maxLength={40} placeholder="e.g. Room 3" />
        </label>
        {live ? (
          <>
            {room.trim() !== (doctor.room ?? "") ? (
              <Button busy={busy} onClick={() => void setLive(true)}>
                Save room
              </Button>
            ) : null}
            <Button busy={busy} onClick={() => void setLive(false)}>
              Go offline
            </Button>
          </>
        ) : (
          <Button variant="primary" busy={busy} onClick={() => void setLive(true)}>
            Go live
          </Button>
        )}
      </div>
    </section>
  );
}
