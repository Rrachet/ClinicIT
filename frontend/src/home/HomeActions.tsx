"use client";

import Link from "next/link";
import { useAuth } from "@/auth/AuthProvider";
import { homeFor } from "@/auth/routing";

/** Sign in, or straight back to work for someone already signed in. */
export function HomeActions({ compact = false }: { compact?: boolean }) {
  const { session } = useAuth();
  const primary = session
    ? { href: homeFor(session.user.role), label: "Open your dashboard" }
    : { href: "/login", label: "Staff sign in" };
  if (compact) {
    return (
      <Link href={primary.href} className="btn btn-primary btn-sm home-nav-cta">
        {primary.label}
      </Link>
    );
  }
  return (
    <div className="home-actions">
      <Link href={primary.href} className="btn btn-primary btn-lg">
        {primary.label}
      </Link>
      <a href="#how-it-works" className="btn btn-secondary btn-lg">
        See how it works
      </a>
    </div>
  );
}
