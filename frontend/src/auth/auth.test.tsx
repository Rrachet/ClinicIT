import { screen, waitFor } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { apiError, fakeApi } from "@/test/fakeApi";
import { session, user } from "@/test/fixtures";
import { navigationMock, router, searchParams } from "@/test/navigation";
import { renderWithAuth } from "@/test/render";
import { LoginScreen } from "./LoginScreen";
import { RequireRole } from "./RequireRole";
import { loadSession } from "./session";

vi.mock("next/navigation", () => navigationMock);

describe("authentication", () => {
  let api: ReturnType<typeof fakeApi>;
  beforeEach(() => {
    api = fakeApi();
    vi.stubGlobal("fetch", api.fetchMock);
    router.replace.mockReset();
    searchParams.value = new URLSearchParams();
  });
  afterEach(() => vi.unstubAllGlobals());

  it("signs a receptionist in, stores the session and routes to reception", async () => {
    api.route("POST /api/v1/auth/login", () => ({
      body: { accessToken: "tok-1", tokenType: "Bearer", expiresAt: "2999-01-01T00:00:00Z", user: user("RECEPTIONIST") },
    }));
    renderWithAuth(<LoginScreen />);

    await userEvent.type(screen.getByLabelText("Email"), "desk@city.test");
    await userEvent.type(screen.getByLabelText("Password"), "correct horse battery");
    await userEvent.click(screen.getByRole("button", { name: "Sign in" }));

    await waitFor(() => expect(router.replace).toHaveBeenCalledWith("/reception"));
    expect(api.calls[0].body).toEqual({ email: "desk@city.test", password: "correct horse battery" });
    expect(loadSession()?.token).toBe("tok-1");
  });

  it("routes a doctor to the doctor console", async () => {
    api.route("POST /api/v1/auth/login", () => ({
      body: { accessToken: "tok-2", tokenType: "Bearer", expiresAt: "2999-01-01T00:00:00Z", user: user("DOCTOR") },
    }));
    renderWithAuth(<LoginScreen />);
    await userEvent.type(screen.getByLabelText("Email"), "dr@city.test");
    await userEvent.type(screen.getByLabelText("Password"), "pw");
    await userEvent.click(screen.getByRole("button", { name: "Sign in" }));
    await waitFor(() => expect(router.replace).toHaveBeenCalledWith("/doctor"));
  });

  it("shows a generic message for wrong credentials and clears the password", async () => {
    api.route("POST /api/v1/auth/login", () => apiError(401, "INVALID_CREDENTIALS", "Invalid email or password"));
    renderWithAuth(<LoginScreen />);
    await userEvent.type(screen.getByLabelText("Email"), "desk@city.test");
    await userEvent.type(screen.getByLabelText("Password"), "nope");
    await userEvent.click(screen.getByRole("button", { name: "Sign in" }));

    expect(await screen.findByRole("alert")).toHaveTextContent("Email or password is incorrect.");
    expect(screen.getByLabelText("Password")).toHaveValue("");
    expect(loadSession()).toBeNull();
  });

  it("explains an expired session", () => {
    searchParams.value = new URLSearchParams("reason=expired");
    renderWithAuth(<LoginScreen />);
    expect(screen.getByText("Your session ended. Please sign in again.")).toBeInTheDocument();
  });

  it("sends signed-out visitors of a protected screen to login", async () => {
    renderWithAuth(
      <RequireRole area="reception">
        <p>secret console</p>
      </RequireRole>,
    );
    await waitFor(() => expect(router.replace).toHaveBeenCalledWith("/login"));
    expect(screen.queryByText("secret console")).not.toBeInTheDocument();
  });

  it("redirects a doctor who opens the reception console to their own screen", async () => {
    renderWithAuth(
      <RequireRole area="reception">
        <p>secret console</p>
      </RequireRole>,
      { session: session("DOCTOR") },
    );
    await waitFor(() => expect(router.replace).toHaveBeenCalledWith("/doctor"));
    expect(screen.queryByText("secret console")).not.toBeInTheDocument();
  });

  it("renders the console for an allowed role", () => {
    renderWithAuth(
      <RequireRole area="reception">
        <p>reception console</p>
      </RequireRole>,
      { session: session("ADMIN") },
    );
    expect(screen.getByText("reception console")).toBeInTheDocument();
  });
});
