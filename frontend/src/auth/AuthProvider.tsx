"use client";

import { createContext, useCallback, useContext, useEffect, useMemo, useSyncExternalStore } from "react";
import { useRouter } from "next/navigation";
import { createApiClient } from "@/api/client";
import { clinicApi, type ClinicApi } from "@/api/clinicApi";
import { API_BASE_URL } from "@/api/config";
import { sessionFrom, type Session } from "./session";
import { sessionStore } from "./sessionStore";

interface AuthContextValue {
  /** undefined until the browser session is known (server render), null when signed out. */
  session: Session | null | undefined;
  api: ClinicApi;
  login: (email: string, password: string) => Promise<Session>;
  logout: () => Promise<void>;
  /** Drop the session locally, e.g. after the server rejected the token. */
  endSession: (reason?: "expired") => void;
}

const AuthContext = createContext<AuthContextValue | null>(null);

export function AuthProvider({ children, apiBaseUrl = API_BASE_URL }: { children: React.ReactNode; apiBaseUrl?: string }) {
  const router = useRouter();
  const session = useSyncExternalStore(sessionStore.subscribe, sessionStore.getSnapshot, sessionStore.getServerSnapshot);

  const endSession = useCallback(
    (reason?: "expired") => {
      sessionStore.set(null);
      router.replace(reason === "expired" ? "/login?reason=expired" : "/login");
    },
    [router],
  );

  // One API client for the whole app; it reads the token at request time.
  const api = useMemo(
    () =>
      clinicApi(
        createApiClient({
          baseUrl: apiBaseUrl,
          getToken: sessionStore.token,
          onUnauthorized: () => endSession("expired"),
        }),
      ),
    [apiBaseUrl, endSession],
  );

  // Sign out locally the moment the token's lifetime ends.
  useEffect(() => {
    if (!session) return;
    const remaining = new Date(session.expiresAt).getTime() - Date.now();
    const timer = setTimeout(() => endSession("expired"), Math.max(0, Math.min(remaining, 2 ** 31 - 1)));
    return () => clearTimeout(timer);
  }, [session, endSession]);

  const login = useCallback(
    async (email: string, password: string) => {
      const next = sessionFrom(await api.login(email, password));
      sessionStore.set(next);
      return next;
    },
    [api],
  );

  const logout = useCallback(async () => {
    try {
      await api.logout();
    } catch {
      // Already invalid on the server; signing out locally is what matters.
    }
    sessionStore.set(null);
    router.replace("/login");
  }, [api, router]);

  const value = useMemo(() => ({ session, api, login, logout, endSession }), [session, api, login, logout, endSession]);
  return <AuthContext.Provider value={value}>{children}</AuthContext.Provider>;
}

export function useAuth(): AuthContextValue {
  const value = useContext(AuthContext);
  if (!value) throw new Error("useAuth must be used inside <AuthProvider>");
  return value;
}

/** The signed-in session; only call below <RequireRole>. */
export function useSession(): Session {
  const { session } = useAuth();
  if (!session) throw new Error("No session: wrap the screen in <RequireRole>");
  return session;
}
