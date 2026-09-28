import { screen, waitFor } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { apiError, fakeApi } from "@/test/fakeApi";
import { router, navigationMock } from "@/test/navigation";
import { session } from "@/test/fixtures";
import { renderWithAuth } from "@/test/render";
import { AccountScreen } from "./AccountScreen";
import { RequireRole } from "./RequireRole";

vi.mock("next/navigation", () => navigationMock);

describe("AccountScreen", () => {
  let api: ReturnType<typeof fakeApi>;

  beforeEach(() => {
    router.replace.mockClear();
    api = fakeApi();
    vi.stubGlobal("fetch", api.fetchMock);
  });
  afterEach(() => vi.unstubAllGlobals());

  async function fill(current: string, next: string, repeat: string) {
    await userEvent.type(screen.getByLabelText("Current password"), current);
    await userEvent.type(screen.getByLabelText(/^New password \(12/), next);
    await userEvent.type(screen.getByLabelText("New password again"), repeat);
  }

  it("changes the password, then signs out everywhere and explains why", async () => {
    api.route("POST /api/v1/auth/password", () => ({ status: 204 }));
    // Rendered as the page renders it: signing out unmounts the screen instead of leaving it without a session.
    renderWithAuth(
      <RequireRole area="account">
        <AccountScreen />
      </RequireRole>,
      { session: session("RECEPTIONIST") },
    );
    expect(screen.getByText(/receptionist@city.test · Receptionist/)).toBeInTheDocument();

    await fill("old-password-123", "a-brand-new-password", "a-brand-new-password");
    await userEvent.click(screen.getByRole("button", { name: "Change password" }));

    await waitFor(() => expect(router.replace).toHaveBeenCalledWith("/login?reason=password-changed"));
    expect(await screen.findByRole("status")).toBeInTheDocument();
    expect(api.callsTo("POST", "/api/v1/auth/password")[0].body).toEqual({
      currentPassword: "old-password-123",
      newPassword: "a-brand-new-password",
    });
  });

  it("will not send mismatched new passwords", async () => {
    renderWithAuth(<AccountScreen />, { session: session("DOCTOR") });
    await fill("old-password-123", "a-brand-new-password", "a-different-password");
    expect(screen.getByText("The new passwords do not match.")).toBeInTheDocument();
    expect(screen.getByRole("button", { name: "Change password" })).toBeDisabled();
  });

  it("shows the server's reason when the current password is wrong", async () => {
    api.route("POST /api/v1/auth/password", () =>
      apiError(400, "INVALID_CURRENT_PASSWORD", "Current password is incorrect"),
    );
    renderWithAuth(<AccountScreen />, { session: session("ADMIN") });
    await fill("wrong-password-1", "a-brand-new-password", "a-brand-new-password");
    await userEvent.click(screen.getByRole("button", { name: "Change password" }));
    expect(await screen.findByRole("alert")).toHaveTextContent("Current password is incorrect");
    expect(router.replace).not.toHaveBeenCalled();
  });
});
