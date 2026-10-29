"use client";

import { useEffect, useRef, useState } from "react";
import { ApiError } from "@/api/client";
import type { PublicQueueStatus } from "@/api/types";
import { useAuth } from "@/auth/AuthProvider";
import { Spinner } from "@/ui/Spinner";
import { minuteRange } from "@/queue/waitEstimate";
import { statusMessage } from "./statusMessage";
import { createAlerter, type Alerter } from "./alerts";

const POLL_MS = 15_000;
/** Near the front (next, or just called) the page checks more often, so the call shows within seconds. */
const NEAR_POLL_MS = 5_000;

/** How often to check: sooner when the patient is about to be (or has just been) called. */
export function pollDelay(status: PublicQueueStatus | null, normalMs: number, nearMs: number): number {
  if (!status) return normalMs;
  const near = status.status === "CALLED" || (status.status === "WAITING" && status.patientsAhead <= 1);
  return near ? nearMs : normalMs;
}

/**
 * Anonymous, mobile-first status page opened from the link reception gives the patient.
 * It polls the public status endpoint; it never touches the staff WebSocket.
 */
export function PatientStatus({
  code,
  pollMs = POLL_MS,
  nearPollMs = NEAR_POLL_MS,
  justCheckedIn = false,
}: {
  code: string;
  pollMs?: number;
  nearPollMs?: number;
  /** Opened straight after self check-in: confirm that their place is set. */
  justCheckedIn?: boolean;
}) {
  const { api } = useAuth();
  const [status, setStatus] = useState<PublicQueueStatus | null>(null);
  const [state, setState] = useState<"loading" | "ok" | "invalid" | "offline">("loading");
  const [updatedAt, setUpdatedAt] = useState<Date | null>(null);
  const delay = pollDelay(status, pollMs, nearPollMs);
  const alerter = useRef<Alerter | null>(null);
  const [alertsOn, setAlertsOn] = useState(false);
  const previousStatus = useRef<string | null>(null);

  // Called: beep and vibrate if the patient turned alerts on, and flag the browser tab.
  useEffect(() => {
    if (!status) return;
    const was = previousStatus.current;
    previousStatus.current = status.status;
    if (status.status === "CALLED") {
      document.title = "Your turn · ClinicIT";
      if (was !== null && was !== "CALLED") alerter.current?.alert();
    } else {
      document.title = "Your place in the queue · ClinicIT";
    }
  }, [status]);

  useEffect(() => {
    let active = true;
    const refresh = () =>
      api.publicStatus(code).then(
        (next) => {
          if (!active) return;
          setStatus(next);
          setState("ok");
          setUpdatedAt(new Date());
        },
        (e: unknown) => {
          if (!active) return;
          if (e instanceof ApiError && e.status === 404) setState("invalid");
          else setState((previous) => (previous === "invalid" ? previous : "offline"));
        },
      );

    void refresh();
    const timer = setInterval(() => {
      if (document.visibilityState === "visible") void refresh();
    }, delay);
    const onVisible = () => {
      if (document.visibilityState === "visible") void refresh();
    };
    document.addEventListener("visibilitychange", onVisible);
    return () => {
      active = false;
      clearInterval(timer);
      document.removeEventListener("visibilitychange", onVisible);
    };
  }, [api, code, delay]);

  if (state === "loading") {
    return (
      <main className="patient">
        <Spinner label="Loading your place in the queue" />
      </main>
    );
  }
  if (state === "invalid") {
    return (
      <main className="patient">
        <div className="patient-card">
          <h1>Link not valid</h1>
          <p>This queue link has expired or is incorrect. Please scan the clinic&apos;s check-in code again.</p>
        </div>
      </main>
    );
  }
  if (!status) {
    return (
      <main className="patient">
        <div className="patient-card">
          <h1>Can&apos;t connect</h1>
          <p>We couldn&apos;t load your queue status. We&apos;ll keep trying.</p>
        </div>
      </main>
    );
  }

  const message = statusMessage(status);
  const open = status.status === "WAITING" || status.status === "CALLED";
  return (
    <main className="patient">
      <div className={`patient-card tone-${message.tone}`}>
        {justCheckedIn && status.status === "WAITING" ? (
          <p className="patient-checked-in" role="status">
            You&apos;re checked in. Your place in the queue is set; keep this page open.
          </p>
        ) : null}
        <p className="patient-clinic">
          {status.clinicName} {status.doctorName ? `· ${status.doctorName}` : ""}
        </p>
        <p className="label">Your token</p>
        <p className="token token-hero" data-testid="patient-token">
          #{status.tokenNumber}
        </p>
        <h1 className="patient-headline" aria-live="polite">
          {message.headline}
        </h1>
        {message.detail ? <p>{message.detail}</p> : null}
        {status.status === "CALLED" && status.room ? (
          <p className="patient-room" data-testid="patient-room">
            {status.room}
          </p>
        ) : null}
        <dl className="patient-facts">
          <div>
            <dt>Now serving</dt>
            <dd data-testid="patient-current">{status.currentToken ? `#${status.currentToken}` : "—"}</dd>
          </div>
          {status.status === "WAITING" ? (
            <div>
              <dt>Ahead of you</dt>
              <dd>{status.patientsAhead}</dd>
            </div>
          ) : null}
          {status.status === "WAITING" && status.estimatedWait ? (
            <div>
              <dt>Estimated wait</dt>
              <dd data-testid="patient-estimate">
                {minuteRange(status.estimatedWait.lowerBoundMinutes, status.estimatedWait.upperBoundMinutes)}
              </dd>
            </div>
          ) : null}
        </dl>
        {status.status === "WAITING" && status.estimatedWait ? (
          <p className="muted small">An estimate that changes as the queue moves, not an appointment time.</p>
        ) : null}
        {open && !alertsOn ? (
          <button
            type="button"
            className="btn btn-secondary btn-md"
            onClick={() => {
              alerter.current = createAlerter();
              alerter.current.test();
              setAlertsOn(true);
            }}
          >
            Alert me with sound and vibration
          </button>
        ) : null}
        {alertsOn && open ? <p className="muted small">Alerts on: this phone will beep and vibrate when it&apos;s your turn.</p> : null}
        <p className="muted small">
          {state === "offline" ? "Connection lost — retrying. " : ""}
          {updatedAt ? `Updated ${updatedAt.toLocaleTimeString([], { hour: "2-digit", minute: "2-digit" })}` : ""}
        </p>
      </div>
    </main>
  );
}
