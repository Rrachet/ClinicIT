"use client";

import { useAuth } from "@/auth/AuthProvider";
import type { FeedStatus } from "@/realtime/queueFeed";
import { longDate } from "./format";

const FEED_TEXT: Record<FeedStatus, string> = {
  connecting: "Connecting…",
  live: "Live",
  offline: "Reconnecting…",
  denied: "Live updates unavailable",
};

export function AppHeader({ title, clinicName, day, feed }: { title: string; clinicName?: string; day?: string; feed?: FeedStatus }) {
  const { session, logout } = useAuth();
  return (
    <header className="app-header">
      <div className="app-header-left">
        <span className="brand">ClinicIT</span>
        <span className="app-title">{title}</span>
        {clinicName ? <span className="muted">{clinicName}</span> : null}
        {day ? <span className="muted">{longDate(day)}</span> : null}
      </div>
      <div className="app-header-right">
        {feed ? (
          <span className={`live live-${feed}`} role="status" aria-live="polite">
            <span className="live-dot" aria-hidden />
            {FEED_TEXT[feed]}
          </span>
        ) : null}
        {session ? <span className="muted">{session.user.fullName}</span> : null}
        <button type="button" className="link-button" onClick={() => void logout()}>
          Sign out
        </button>
      </div>
    </header>
  );
}
