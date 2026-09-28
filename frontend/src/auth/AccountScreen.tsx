"use client";

import { useState } from "react";

import { ApiError } from "@/api/client";
import { AppHeader } from "@/ui/AppHeader";
import { Button } from "@/ui/Button";
import { ErrorBanner } from "@/ui/Feedback";
import { useAuth, useSession } from "./AuthProvider";

const ROLE: Record<string, string> = { ADMIN: "Admin", RECEPTIONIST: "Receptionist", DOCTOR: "Doctor" };

/**
 * The signed-in person's account: who they are, and changing their password. The server
 * checks the current password and ends every session of theirs, so they sign in again.
 */
export function AccountScreen() {
  const { api, endSession } = useAuth();
  const session = useSession();
  const [current, setCurrent] = useState("");
  const [next, setNext] = useState("");
  const [repeat, setRepeat] = useState("");
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<unknown>(null);
  const mismatch = repeat.length > 0 && next !== repeat;

  async function submit(e: React.FormEvent) {
    e.preventDefault();
    if (next !== repeat) return;
    setBusy(true);
    setError(null);
    try {
      await api.changePassword(current, next);
      endSession("password-changed");
    } catch (err) {
      // A wrong current password is a 400 with the server's own explanation.
      setError(err instanceof ApiError ? err : new ApiError(0, "UNKNOWN", "The password could not be changed."));
      setBusy(false);
    }
  }

  return (
    <>
      <AppHeader title="My account" />
      <main className="page stack" style={{ maxWidth: "36rem" }}>
        <section className="panel" aria-labelledby="me-title">
          <h2 id="me-title">{session.user.fullName}</h2>
          <p>
            {session.user.email} · {ROLE[session.user.role] ?? session.user.role}
          </p>
        </section>
        <form className="panel stack" aria-labelledby="password-title" onSubmit={submit}>
          <h2 id="password-title">Change password</h2>
          <ErrorBanner error={error} onDismiss={() => setError(null)} />
          <label className="field">
            <span>Current password</span>
            <input type="password" autoComplete="current-password" value={current} onChange={(e) => setCurrent(e.target.value)} required />
          </label>
          <label className="field">
            <span>New password (12+ characters)</span>
            <input type="password" autoComplete="new-password" value={next} onChange={(e) => setNext(e.target.value)} required minLength={12} maxLength={72} />
          </label>
          <label className="field">
            <span>New password again</span>
            <input type="password" autoComplete="new-password" value={repeat} onChange={(e) => setRepeat(e.target.value)} required aria-invalid={mismatch || undefined} />
          </label>
          {mismatch ? <p className="hint warn-text">The new passwords do not match.</p> : null}
          <p className="hint">You will be signed out on every device and sign in again with the new password.</p>
          <div className="row">
            <Button type="submit" variant="primary" busy={busy} disabled={!current || next.length < 12 || next !== repeat}>
              Change password
            </Button>
          </div>
        </form>
      </main>
    </>
  );
}
