"use client";

import { useCallback, useState } from "react";
import type { ClinicApi } from "@/api/clinicApi";
import type { NotificationType, PatientNotification } from "@/api/types";
import { Button } from "@/ui/Button";
import { ErrorBanner } from "@/ui/Feedback";
import { useAsync } from "@/ui/useAsync";
import { statusLink } from "./workflows";

export interface LinkTarget {
  appointmentId: string;
  code: string;
  token: number;
}

const TYPE_LABEL: Record<NotificationType, string> = {
  APPOINTMENT_CONFIRMED: "Appointment confirmed",
  PATIENT_JOINED_QUEUE: "Queue link",
  PATIENT_NEAR_TURN: "Nearly your turn",
  PATIENT_CALLED: "Your turn",
};

const STATUS_LABEL = { PENDING: "Sending", SENT: "Sent", FAILED: "Failed" } as const;

/**
 * What was sent to the patient (the queue link goes out automatically when they join),
 * with a retry for failed messages and the link itself as a manual fallback.
 */
export function StatusLinkDialog({ api, link, onClose }: { api: ClinicApi; link: LinkTarget | null; onClose: () => void }) {
  if (!link) return null;
  return <StatusLinkDialogBody api={api} link={link} onClose={onClose} />;
}

function StatusLinkDialogBody({ api, link, onClose }: { api: ClinicApi; link: LinkTarget; onClose: () => void }) {
  const [copied, setCopied] = useState(false);
  const [retrying, setRetrying] = useState<string | null>(null);
  const [error, setError] = useState<unknown>(null);
  const messages = useAsync(useCallback(() => api.notifications(link.appointmentId), [api, link.appointmentId]));
  const url = statusLink(link.code);

  async function retry(message: PatientNotification) {
    setRetrying(message.id);
    setError(null);
    try {
      await api.retryNotification(message.id);
      messages.reload();
    } catch (e) {
      setError(e);
    } finally {
      setRetrying(null);
    }
  }

  return (
    <div className="overlay">
      <div className="dialog dialog-wide" role="dialog" aria-modal="true" aria-labelledby="link-title">
        <h2 id="link-title">Messages for token #{link.token}</h2>
        <ErrorBanner error={error ?? messages.error} onDismiss={() => setError(null)} />

        {messages.loading ? (
          <p className="muted">Loading…</p>
        ) : (messages.data ?? []).length === 0 ? (
          <p className="muted">No messages yet.</p>
        ) : (
          <ul className="messages" aria-label="Messages sent to the patient">
            {(messages.data ?? []).map((m) => (
              <li key={m.id}>
                <div className="message-head">
                  <strong>{TYPE_LABEL[m.type]}</strong>
                  <span className="muted">
                    {m.channel} to {m.recipient}
                  </span>
                  <span className={`badge badge-msg-${m.status.toLowerCase()}`}>{STATUS_LABEL[m.status]}</span>
                  {m.status === "FAILED" ? (
                    <Button size="sm" busy={retrying === m.id} onClick={() => void retry(m)}>
                      Retry
                    </Button>
                  ) : null}
                </div>
                <p className="message-body">{m.body}</p>
                {m.status !== "SENT" && m.lastError ? (
                  <p className="muted small">
                    Attempt {m.attempts} of {m.maxAttempts} failed ({m.lastError})
                  </p>
                ) : null}
              </li>
            ))}
          </ul>
        )}

        <label className="field">
          <span>Status link (only for this patient; stops working after today)</span>
          <input className="link-box" value={url} readOnly onFocus={(e) => e.target.select()} />
        </label>
        <div className="dialog-actions">
          <Button onClick={() => messages.reload()}>Refresh</Button>
          <Button
            onClick={async () => {
              await navigator.clipboard?.writeText(url);
              setCopied(true);
            }}
          >
            {copied ? "Copied" : "Copy link"}
          </Button>
          <Button variant="primary" onClick={onClose} autoFocus>
            Done
          </Button>
        </div>
      </div>
    </div>
  );
}
