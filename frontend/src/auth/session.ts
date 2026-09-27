import type { LoginResponse, User } from "@/api/types";

/**
 * The signed-in staff session. Kept in sessionStorage: it survives a page reload but not
 * closing the tab, and is never sent automatically (the API uses a bearer header, not
 * cookies). See docs/FRONTEND.md for the trade-off.
 */
export interface Session {
  token: string;
  expiresAt: string;
  user: User;
}

const KEY = "clinicit.session";

export function sessionFrom(login: LoginResponse): Session {
  return { token: login.accessToken, expiresAt: login.expiresAt, user: login.user };
}

export function isExpired(session: Session, now: Date = new Date()): boolean {
  return new Date(session.expiresAt).getTime() <= now.getTime();
}

export function loadSession(now: Date = new Date()): Session | null {
  try {
    const raw = sessionStorage.getItem(KEY);
    if (!raw) return null;
    const session = JSON.parse(raw) as Session;
    if (!session?.token || !session.user || isExpired(session, now)) {
      sessionStorage.removeItem(KEY);
      return null;
    }
    return session;
  } catch {
    return null;
  }
}

export function saveSession(session: Session) {
  sessionStorage.setItem(KEY, JSON.stringify(session));
}

export function clearSession() {
  sessionStorage.removeItem(KEY);
}
