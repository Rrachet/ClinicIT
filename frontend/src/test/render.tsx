import { render } from "@testing-library/react";
import type { ReactElement } from "react";
import { AuthProvider } from "@/auth/AuthProvider";
import type { Session } from "@/auth/session";
import { saveSession } from "@/auth/session";
import { sessionStore } from "@/auth/sessionStore";

export const API = "http://api.test";

/** Renders a screen inside the real AuthProvider, optionally already signed in. */
export function renderWithAuth(ui: ReactElement, options: { session?: Session } = {}) {
  sessionStorage.clear();
  if (options.session) saveSession(options.session);
  sessionStore.resetForTests();
  return render(<AuthProvider apiBaseUrl={API}>{ui}</AuthProvider>);
}
