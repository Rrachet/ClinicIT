"use client";

import { useEffect } from "react";
import { useRouter } from "next/navigation";
import { useAuth } from "@/auth/AuthProvider";
import { homeFor } from "@/auth/routing";
import { FullPageSpinner } from "@/ui/Spinner";

export default function Home() {
  const { session } = useAuth();
  const router = useRouter();
  useEffect(() => {
    if (session === null) router.replace("/login");
    else if (session) router.replace(homeFor(session.user.role));
  }, [session, router]);
  return <FullPageSpinner label="Loading" />;
}
