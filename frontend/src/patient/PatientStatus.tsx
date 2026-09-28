"use client";

import { useEffect, useState } from "react";
import { ApiError } from "@/api/client";
import type { PublicQueueStatus } from "@/api/types";
import { useAuth } from "@/auth/AuthProvider";
import { Spinner } from "@/ui/Spinner";
import { minuteRange } from "@/queue/waitEstimate";
import { statusMessage } from "./statusMessage";

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
}: {
  code: string;
  pollMs?: number;
  nearPollMs?: number;
}) {
  const { api } = useAuth();
  const [status, setStatus] = useState<PublicQueueStatus | null>(null);
  const [state, setState] = useState<"loading" | "ok" | "invalid" | "offline">("loading");
  const [updatedAt, setUpdatedAt] = useState<Date | null>(null);
  const delay = pollDelay(status, pollMs, nearPollMs);

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
          <p>This queue link has expired or is incorrect. Please ask at reception.</p>
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
  return (
    <main className="patient">
      <div className={`patient-card tone-${message.tone}`}>
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
        <p className="muted small">
          {state === "offline" ? "Connection lost — retrying. " : ""}
          {updatedAt ? `Updated ${updatedAt.toLocaleTimeString([], { hour: "2-digit", minute: "2-digit" })}` : ""}
        </p>
      </div>
    </main>
  );
}
