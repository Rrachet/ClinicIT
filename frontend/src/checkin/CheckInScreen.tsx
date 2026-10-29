"use client";

import { useCallback, useEffect, useId, useState } from "react";
import { useRouter } from "next/navigation";
import { ApiError } from "@/api/client";
import type { PublicCheckIn } from "@/api/types";
import { useAuth } from "@/auth/AuthProvider";
import { Button } from "@/ui/Button";
import { Spinner } from "@/ui/Spinner";

/** Where the patient lands after checking in; the status page greets them once. */
export function statusPath(code: string): string {
  return `/status/${encodeURIComponent(code)}?checkedIn=1`;
}

/**
 * The page a patient opens by scanning the clinic's QR code: name, phone, what they are here
 * for, and optionally a few words for the doctor. They are then queued with a live doctor
 * who sees that visit type, with no receptionist (docs/SELF_CHECK_IN.md).
 */
export function CheckInScreen({ code }: { code: string }) {
  const { api } = useAuth();
  const router = useRouter();
  const ids = useId();
  const [page, setPage] = useState<PublicCheckIn | null>(null);
  const [state, setState] = useState<"loading" | "ok" | "invalid" | "offline">("loading");
  const [fullName, setFullName] = useState("");
  const [phone, setPhone] = useState("");
  const [visitTypeId, setVisitTypeId] = useState<string | null>(null);
  const [reason, setReason] = useState("");
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);

  const load = useCallback(
    () =>
      api.publicCheckIn(code).then(
        (next) => {
          setPage(next);
          setState("ok");
          // Keep the choice only while it can still be seen.
          setVisitTypeId((current) => (next.visitTypes.some((t) => t.id === current && t.available) ? current : null));
        },
        (e: unknown) => setState(e instanceof ApiError && e.status === 404 ? "invalid" : "offline"),
      ),
    [api, code],
  );

  useEffect(() => {
    void load();
    // Doctors go live and offline during the day; keep the options current.
    const timer = setInterval(() => void load(), 30_000);
    return () => clearInterval(timer);
  }, [load]);

  async function submit(e: React.FormEvent) {
    e.preventDefault();
    if (!visitTypeId) {
      setError("Please choose what you are here for.");
      return;
    }
    setBusy(true);
    setError(null);
    try {
      const result = await api.selfCheckIn(code, {
        fullName: fullName.trim(),
        phone: phone.trim(),
        visitTypeId,
        reason: reason.trim() || undefined,
      });
      router.push(statusPath(result.statusCode));
    } catch (err) {
      setBusy(false);
      if (err instanceof ApiError && err.code === "NO_DOCTOR_AVAILABLE") {
        setError("No doctor for this visit is available right now. Please choose another option or ask at the clinic.");
        void load();
      } else if (err instanceof ApiError && err.status === 429) {
        setError("Too many check-ins from this network. Please wait a few minutes and try again.");
      } else if (err instanceof ApiError && err.status === 400) {
        setError(err.message.startsWith("phone") ? "Please enter a valid phone number." : "Please check your details.");
      } else if (err instanceof ApiError && err.status === 404) {
        setError("That option is no longer offered. Please choose another.");
        void load();
      } else {
        setError("We couldn't check you in. Please check your connection and try again.");
      }
    }
  }

  if (state === "loading") {
    return (
      <main className="patient">
        <Spinner label="Loading check-in" />
      </main>
    );
  }
  if (state === "invalid" || !page) {
    return (
      <main className="patient">
        <div className="patient-card">
          <h1>{state === "invalid" ? "Check-in code not valid" : "Can't connect"}</h1>
          <p>
            {state === "invalid"
              ? "Please scan the check-in QR code displayed in the clinic again."
              : "We couldn't reach the clinic. Please check your connection; we'll keep trying."}
          </p>
        </div>
      </main>
    );
  }

  const available = page.visitTypes.filter((t) => t.available);
  return (
    <main className="patient">
      <form className="patient-card checkin-card" onSubmit={submit} aria-labelledby={`${ids}-title`}>
        <p className="patient-clinic">{page.clinicName}</p>
        <h1 id={`${ids}-title`} className="patient-headline">
          Check in
        </h1>
        <p className="checkin-emergency" role="note">
          Not for emergencies. If you need urgent help, tell clinic staff now or call 112.
        </p>

        {!page.open || available.length === 0 ? (
          <div className="checkin-closed" role="status">
            <strong>No doctor is seeing patients right now.</strong>
            <span>This page updates by itself when a doctor is ready.</span>
          </div>
        ) : (
          <>
            {error ? (
              <div className="banner banner-error" role="alert">
                {error}
              </div>
            ) : null}
            <label className="field">
              <span>Your name (or the patient&apos;s name)</span>
              <input
                value={fullName}
                onChange={(e) => setFullName(e.target.value)}
                required
                maxLength={150}
                autoComplete="name"
              />
            </label>
            <label className="field">
              <span>Phone number</span>
              <input
                value={phone}
                onChange={(e) => setPhone(e.target.value)}
                required
                maxLength={30}
                inputMode="tel"
                autoComplete="tel"
                placeholder="e.g. 98765 43210"
              />
            </label>
            <fieldset className="checkin-options">
              <legend>What are you here for?</legend>
              {page.visitTypes.map((type) => (
                <label key={type.id} className={`checkin-option${type.available ? "" : " checkin-option-off"}`}>
                  <input
                    type="radio"
                    name="visitType"
                    value={type.id}
                    checked={visitTypeId === type.id}
                    disabled={!type.available}
                    onChange={() => setVisitTypeId(type.id)}
                  />
                  <span>
                    <strong>{type.name}</strong>
                    {type.description ? <span className="muted small">{type.description}</span> : null}
                    {type.available ? null : <span className="muted small">No doctor available right now</span>}
                  </span>
                </label>
              ))}
            </fieldset>
            <label className="field">
              <span>Anything the doctor should know? (optional)</span>
              <textarea value={reason} onChange={(e) => setReason(e.target.value)} maxLength={300} rows={3} />
            </label>
            <p className="muted small">Your name and number are used only by this clinic to manage your visit.</p>
            <Button type="submit" variant="primary" size="lg" busy={busy}>
              Check in
            </Button>
          </>
        )}
      </form>
    </main>
  );
}
