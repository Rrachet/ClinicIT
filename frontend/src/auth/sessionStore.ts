import { clearSession, loadSession, saveSession, type Session } from "./session";

/**
 * The session as an external store for useSyncExternalStore: one source of truth that
 * both React (screens) and non-React code (the API client's token lookup) read.
 * The server snapshot is `undefined` ("not known yet"), so server-rendered HTML never
 * depends on the browser's sessionStorage and hydration stays consistent.
 */
type Listener = () => void;

let current: Session | null | undefined = undefined;
const listeners = new Set<Listener>();

function read(): Session | null {
  if (current === undefined) current = loadSession();
  return current;
}

export const sessionStore = {
  subscribe(listener: Listener) {
    listeners.add(listener);
    return () => listeners.delete(listener);
  },
  getSnapshot: (): Session | null => read(),
  getServerSnapshot: (): Session | null | undefined => undefined,
  token: (): string | null => read()?.token ?? null,
  set(session: Session | null) {
    if (session) saveSession(session);
    else clearSession();
    current = session;
    listeners.forEach((listener) => listener());
  },
  /** Tests only: forget the cached value so the next read goes to storage. */
  resetForTests() {
    current = undefined;
  },
};
