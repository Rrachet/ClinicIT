"use client";

import { useEffect } from "react";
import { useRouter } from "next/navigation";
import { useAuth } from "./AuthProvider";
import { canEnter, homeFor, type Area } from "./routing";
import { FullPageSpinner } from "@/ui/Spinner";

/** Renders children only for a signed-in user whose role belongs in this area. */
export function RequireRole({ area, children }: { area: Area; children: React.ReactNode }) {
  const { session } = useAuth();
  const router = useRouter();
  const allowed = session ? canEnter(session.user.role, area) : false;

  useEffect(() => {
    if (session === null) router.replace("/login");
    else if (session && !allowed) router.replace(homeFor(session.user.role));
  }, [session, allowed, router]);

  if (!session || !allowed) return <FullPageSpinner label="Loading" />;
  return <>{children}</>;
}
