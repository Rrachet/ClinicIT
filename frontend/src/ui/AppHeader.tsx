"use client";

import Link from "next/link";
import { usePathname } from "next/navigation";

import { useAuth } from "@/auth/AuthProvider";
import type { FeedStatus } from "@/realtime/queueFeed";
import { longDate } from "./format";

const FEED_TEXT: Record<FeedStatus, string> = {
  connecting: "Connecting…",
  live: "Live",
  offline: "Reconnecting…",
  denied: "Live updates unavailable",
};

/** Admins work at the desk and also look after the clinic's figures. */
const ADMIN_LINKS = [
  { href: "/reception", label: "Reception" },
  { href: "/admin", label: "Analytics" },
  { href: "/admin/schedules", label: "Schedules" },
];

export function AppHeader({ title, clinicName, day, feed }: { title: string; clinicName?: string; day?: string; feed?: FeedStatus }) {
  const { session, logout } = useAuth();
  const pathname = usePathname();
  return (
    <header className="app-header">
      <div className="app-header-left">
        <span className="brand">ClinicIT</span>
        <span className="app-title">{title}</span>
        {clinicName ? <span className="muted">{clinicName}</span> : null}
        {day ? <span className="muted">{longDate(day)}</span> : null}
        {session?.user.role === "ADMIN" ? (
          <nav className="app-nav" aria-label="Sections">
            {ADMIN_LINKS.map(({ href, label }) => (
              <Link key={href} href={href} aria-current={pathname === href ? "page" : undefined}>
                {label}
              </Link>
            ))}
          </nav>
        ) : null}
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
