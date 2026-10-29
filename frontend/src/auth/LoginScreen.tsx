"use client";

import { useEffect, useState } from "react";
import Link from "next/link";
import { useRouter, useSearchParams } from "next/navigation";
import { ApiError } from "@/api/client";
import { API_CONFIGURED } from "@/api/config";
import { Button } from "@/ui/Button";
import { ErrorBanner, Notice } from "@/ui/Feedback";
import { useAuth } from "./AuthProvider";
import { homeFor } from "./routing";
import { BrandMark } from "@/ui/BrandMark";

export function LoginScreen({ apiConfigured = API_CONFIGURED }: { apiConfigured?: boolean }) {
  const { login, session } = useAuth();
  const router = useRouter();
  const [email, setEmail] = useState("");
  const [password, setPassword] = useState("");
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<unknown>(null);
  const reason = useSearchParams().get("reason");

  useEffect(() => {
    if (session) router.replace(homeFor(session.user.role));
  }, [session, router]);

  async function submit(e: React.FormEvent) {
    e.preventDefault();
    setBusy(true);
    setError(null);
    try {
      const next = await login(email, password);
      router.replace(homeFor(next.user.role));
    } catch (err) {
      setError(
        err instanceof ApiError && err.status === 401
          ? new ApiError(401, err.code, "Email or password is incorrect.")
          : err,
      );
      setPassword("");
    } finally {
      setBusy(false);
    }
  }

  return (
    <main className="login">
      <form className="login-card" onSubmit={submit} aria-labelledby="login-title">
        <Link href="/" className="brand login-brand">
          <BrandMark size={26} /> ClinicIT
        </Link>
        <h1 id="login-title">Sign in</h1>
        {reason === "expired" ? <Notice>Your session ended. Please sign in again.</Notice> : null}
        {reason === "password-changed" ? (
          <Notice>Password changed. You were signed out everywhere; sign in with your new password.</Notice>
        ) : null}
        {apiConfigured ? null : (
          <Notice>This site is not connected to a ClinicIT server yet, so signing in is not possible.</Notice>
        )}
        <ErrorBanner error={error} />
        <label className="field">
          <span>Email</span>
          <input type="email" autoComplete="username" value={email} onChange={(e) => setEmail(e.target.value)} required autoFocus />
        </label>
        <label className="field">
          <span>Password</span>
          <input
            type="password"
            autoComplete="current-password"
            value={password}
            onChange={(e) => setPassword(e.target.value)}
            required
          />
        </label>
        <Button type="submit" variant="primary" size="lg" busy={busy} disabled={!apiConfigured}>
          Sign in
        </Button>
        <p className="muted small">
          Accounts are created by your clinic admin. <Link href="/">About ClinicIT</Link>
        </p>
      </form>
    </main>
  );
}
