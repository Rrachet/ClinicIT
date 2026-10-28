"use client";

import { useEffect } from "react";
import { useRouter } from "next/navigation";
import { loginPath, useAuth } from "./AuthProvider";
import { sessionStore } from "./sessionStore";
import { canEnter, homeFor, type Area } from "./routing";
import { FullPageSpinner } from "@/ui/Spinner";

/** Renders children only for a signed-in user whose role belongs in this area. */
export function RequireRole({ area, children }: { area: Area; children: React.ReactNode }) {
  const { session } = useAuth();
  const router = useRouter();
  const allowed = session ? canEnter(session.user.role, area) : false;

  useEffect(() => {
    // An ended session keeps its reason, so this redirect does not overwrite endSession's.
    if (session === null) router.replace(loginPath(sessionStore.endReason()));
    else if (session && !allowed) router.replace(homeFor(session.user.role));
  }, [session, allowed, router]);

  if (!session || !allowed) return <FullPageSpinner label="Loading" />;
  return <>{children}</>;
}
