"use client";

import Link from "next/link";
import { useCallback, useEffect, useRef, useState } from "react";
import { ApiError } from "@/api/client";
import type { ReceptionistAction, ReceptionistInfo } from "@/api/types";
import { useAuth } from "@/auth/AuthProvider";
import { BrandMark } from "@/ui/BrandMark";
import { Spinner } from "@/ui/Spinner";

interface Line {
  from: "patient" | "receptionist";
  text: string;
  actions?: ReceptionistAction[];
}

interface Saved {
  conversationId: string | null;
  lines: Line[];
}

const QUICK_REPLIES = ["I'm at the clinic now", "I'd like to book an appointment"];

const storageKey = (code: string) => `clinicit.receptionist.${code}`;

function load(code: string): Saved | null {
  try {
    const raw = sessionStorage.getItem(storageKey(code));
    return raw ? (JSON.parse(raw) as Saved) : null;
  } catch {
    return null;
  }
}

function store(code: string, saved: Saved) {
  try {
    sessionStorage.setItem(storageKey(code), JSON.stringify(saved));
  } catch {
    // Private mode or storage full: the chat still works until the page is closed.
  }
}

function when(startsAt: string): string {
  const [date, time] = startsAt.split("T");
  const day = new Date(`${date}T00:00:00`).toLocaleDateString([], { weekday: "long", day: "numeric", month: "long" });
  return `${day} at ${time.slice(0, 5)}`;
}

/**
 * The AI receptionist, opened from the clinic's QR code: the patient chats, and the
 * receptionist checks them in now or books a time. It can only act through ClinicIT's own rules
 * (docs/AI_RECEPTIONIST.md). The check-in form is always one tap away.
 */
export function ReceptionistChat({ code }: { code: string }) {
  const { api } = useAuth();
  const [info, setInfo] = useState<ReceptionistInfo | null>(null);
  const [state, setState] = useState<"loading" | "ok" | "invalid" | "offline">("loading");
  const [conversationId, setConversationId] = useState<string | null>(null);
  const [lines, setLines] = useState<Line[]>([]);
  const [draft, setDraft] = useState("");
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const endRef = useRef<HTMLDivElement>(null);

  useEffect(() => {
    let active = true;
    api.receptionist(code).then(
      (next) => {
        if (!active) return;
        setInfo(next);
        setState("ok");
        const saved = load(code);
        if (saved) {
          setConversationId(saved.conversationId);
          setLines(saved.lines);
        } else {
          setLines([{ from: "receptionist", text: next.greeting }]);
        }
      },
      (e: unknown) => active && setState(e instanceof ApiError && e.status === 404 ? "invalid" : "offline"),
    );
    return () => {
      active = false;
    };
  }, [api, code]);

  useEffect(() => {
    endRef.current?.scrollIntoView?.({ block: "end" });
  }, [lines, busy]);

  const send = useCallback(
    async (text: string) => {
      const message = text.trim();
      if (!message || busy) return;
      setDraft("");
      setError(null);
      const withPatient: Line[] = [...lines, { from: "patient", text: message }];
      setLines(withPatient);
      setBusy(true);
      try {
        const reply = await api.receptionistMessage(code, { conversationId: conversationId ?? undefined, message });
        const next: Line[] = [...withPatient, { from: "receptionist", text: reply.reply, actions: reply.actions }];
        setLines(next);
        setConversationId(reply.conversationId);
        store(code, { conversationId: reply.conversationId, lines: next });
      } catch (e) {
        setLines(lines);
        setDraft(message);
        setError(
          e instanceof ApiError && e.status === 429
            ? "Too many messages from this network. Please wait a minute and try again."
            : e instanceof ApiError && e.status === 503
              ? "The receptionist isn't available right now. Please use the check-in form below."
              : "Your message didn't send. Please check your connection and try again.",
        );
      } finally {
        setBusy(false);
      }
    },
    [api, busy, code, conversationId, lines],
  );

  if (state === "loading") {
    return (
      <main className="patient">
        <Spinner label="Loading" />
      </main>
    );
  }
  if (state === "invalid" || !info) {
    return (
      <main className="patient">
        <div className="patient-card">
          <h1>{state === "invalid" ? "Code not valid" : "Can't connect"}</h1>
          <p>
            {state === "invalid"
              ? "Please scan the QR code displayed in the clinic again."
              : "We couldn't reach the clinic. Please check your connection and reload."}
          </p>
        </div>
      </main>
    );
  }
  if (!info.enabled) {
    return (
      <main className="patient">
        <div className="patient-card">
          <p className="patient-clinic">{info.clinicName}</p>
          <h1 className="patient-headline">Check in</h1>
          <p>Chat isn&apos;t available at this clinic. You can check in with a short form instead.</p>
          <Link className="btn btn-primary btn-lg" href={`/check-in/${code}`}>
            Open the check-in form
          </Link>
        </div>
      </main>
    );
  }

  const started = lines.some((l) => l.from === "patient");
  return (
    <main className="chat">
      <header className="chat-head">
        <BrandMark size={30} />
        <div>
          <strong>{info.clinicName}</strong>
          <span className="muted small">Receptionist · usually replies in a few seconds</span>
        </div>
      </header>
      <p className="checkin-emergency chat-emergency" role="note">
        Not for emergencies. If you need urgent help, tell clinic staff now or call 112.
      </p>

      <ol className="chat-lines" aria-label="Conversation" aria-live="polite">
        {lines.map((line, i) => (
          <li key={i} className={`chat-line chat-${line.from}`}>
            <span className="sr-only">{line.from === "patient" ? "You:" : "Receptionist:"}</span>
            <p>{line.text}</p>
            {line.actions?.map((action, j) =>
              action.type === "QUEUED" && action.statusCode ? (
                <div key={j} className="chat-card chat-card-go">
                  <span className="label">You&apos;re checked in</span>
                  <span className="token token-xl">#{action.tokenNumber}</span>
                  <span>
                    {action.doctorName}
                    {action.room ? ` · ${action.room}` : ""}
                  </span>
                  <Link className="btn btn-primary btn-md" href={`/status/${action.statusCode}?checkedIn=1`}>
                    Open my place in the queue
                  </Link>
                </div>
              ) : action.type === "BOOKED" && action.startsAt ? (
                <div key={j} className="chat-card">
                  <span className="label">Appointment booked</span>
                  <strong>{when(action.startsAt)}</strong>
                  <span>
                    {action.doctorName}
                    {action.room ? ` · ${action.room}` : ""}
                  </span>
                  <span className="muted small">When you arrive, scan the clinic&apos;s QR code again to check in.</span>
                </div>
              ) : null,
            )}
          </li>
        ))}
        {busy ? (
          <li className="chat-line chat-receptionist chat-typing" aria-label="Receptionist is typing">
            <span />
            <span />
            <span />
          </li>
        ) : null}
      </ol>
      <div ref={endRef} />

      {!started && !busy ? (
        <div className="chat-quick" aria-label="Suggested replies">
          {QUICK_REPLIES.map((q) => (
            <button key={q} type="button" className="btn btn-secondary btn-md" onClick={() => void send(q)}>
              {q}
            </button>
          ))}
        </div>
      ) : null}

      {error ? (
        <div className="banner banner-error" role="alert">
          {error}
        </div>
      ) : null}

      <form
        className="chat-input"
        onSubmit={(e) => {
          e.preventDefault();
          void send(draft);
        }}
      >
        <label className="sr-only" htmlFor="chat-message">
          Message
        </label>
        <input
          id="chat-message"
          value={draft}
          onChange={(e) => setDraft(e.target.value)}
          placeholder="Type a message…"
          maxLength={500}
          autoComplete="off"
          disabled={busy}
        />
        <button type="submit" className="btn btn-primary btn-md" disabled={busy || !draft.trim()}>
          Send
        </button>
      </form>
      <p className="muted small chat-foot">
        Prefer a form? <Link href={`/check-in/${code}`}>Check in here</Link>. Your name and number are used only by this
        clinic, and the chat is deleted after a day.
      </p>
    </main>
  );
}
