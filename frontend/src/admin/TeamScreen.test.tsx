import { screen, waitFor, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import type { User } from "@/api/types";
import { apiError, fakeApi } from "@/test/fakeApi";
import { CLINIC, MEHTA, session, SHARMA, user } from "@/test/fixtures";
import { navigationMock } from "@/test/navigation";
import { renderWithAuth } from "@/test/render";
import { TeamScreen } from "./TeamScreen";

vi.mock("next/navigation", () => navigationMock);

describe("TeamScreen", () => {
  let api: ReturnType<typeof fakeApi>;
  let users: User[];

  beforeEach(() => {
    users = [
      user("ADMIN", { fullName: "Asha Admin" }),
      user("DOCTOR", { id: "user-sharma", email: "sharma@city.test", fullName: "Dr. Sharma" }),
      user("RECEPTIONIST", { id: "user-desk", fullName: "Desk Person" }),
    ];
    api = fakeApi()
      .route("GET /api/v1/clinic", () => ({ body: CLINIC }))
      .route("GET /api/v1/doctors", () => ({ body: [SHARMA, MEHTA] }))
      .route("GET /api/v1/users", () => ({ body: users }));
    vi.stubGlobal("fetch", api.fetchMock);
  });
  afterEach(() => vi.unstubAllGlobals());

  async function open() {
    renderWithAuth(<TeamScreen />, { session: session("ADMIN") });
    await screen.findByRole("heading", { name: "Staff accounts" });
  }

  it("lists doctors with their logins and staff with their roles", async () => {
    await open();
    const doctors = screen.getByRole("table", { name: "Doctors" });
    expect(within(doctors).getByRole("row", { name: /Dr. Sharma/ })).toHaveTextContent("sharma@city.test");
    expect(within(doctors).getByRole("row", { name: /Dr. Mehta/ })).toHaveTextContent("No login yet");

    const staff = screen.getByRole("table", { name: "Staff accounts" });
    expect(within(staff).getByRole("row", { name: /Desk Person/ })).toHaveTextContent("Receptionist");
    // An admin cannot disable their own account (the server refuses it too).
    expect(within(staff).queryByRole("button", { name: "Disable Asha Admin" })).not.toBeInTheDocument();
  });

  it("creates a doctor login only for a doctor who has none yet", async () => {
    api.route("POST /api/v1/users", ({ body }) => ({ status: 201, body: { ...user("DOCTOR"), ...(body as object), id: "new" } }));
    await open();
    const form = screen.getByRole("form", { name: "Add a staff account" });
    await userEvent.type(within(form).getByLabelText("Full name"), "Dr. Mehta");
    await userEvent.type(within(form).getByLabelText("Email"), "mehta@city.test");
    await userEvent.selectOptions(within(form).getByLabelText("Role"), "DOCTOR");
    const profile = within(form).getByLabelText("Doctor profile") as HTMLSelectElement;
    expect([...profile.options].map((o) => o.textContent)).toEqual(["Dr. Mehta"]);
    await userEvent.type(within(form).getByLabelText(/Initial password/), "a-long-initial-pass");
    await userEvent.click(within(form).getByRole("button", { name: "Create account" }));

    await waitFor(() => expect(api.callsTo("POST", "/api/v1/users")).toHaveLength(1));
    expect(api.callsTo("POST", "/api/v1/users")[0].body).toEqual({
      email: "mehta@city.test",
      fullName: "Dr. Mehta",
      password: "a-long-initial-pass",
      role: "DOCTOR",
      doctorProfileId: MEHTA.id,
    });
    expect(await screen.findByText(/Dr. Mehta can now sign in as doctor/)).toBeInTheDocument();
  });

  it("asks before disabling an account and explains what happens", async () => {
    api.route("POST /api/v1/users/user-desk/disable", () => ({ body: { ...users[2], enabled: false } }));
    await open();
    await userEvent.click(screen.getByRole("button", { name: "Disable Desk Person" }));
    const dialog = screen.getByRole("alertdialog", { name: "Disable Desk Person?" });
    expect(dialog).toHaveTextContent("signed out everywhere");
    await userEvent.click(within(dialog).getByRole("button", { name: "Disable account" }));
    await waitFor(() => expect(api.callsTo("POST", "/api/v1/users/user-desk/disable")).toHaveLength(1));
  });

  it("adds a doctor and renames the clinic", async () => {
    api
      .route("POST /api/v1/doctors", ({ body }) => ({ status: 201, body: { ...MEHTA, id: "doc-new", ...(body as object) } }))
      .route("PUT /api/v1/clinic", ({ body }) => ({ body: { ...CLINIC, ...(body as object) } }));
    await open();
    await userEvent.type(screen.getByLabelText("Doctor's name"), "Dr. Anjali Rao");
    await userEvent.click(screen.getByRole("button", { name: "Add doctor" }));
    await waitFor(() =>
      expect(api.callsTo("POST", "/api/v1/doctors")[0]?.body).toEqual({ displayName: "Dr. Anjali Rao" }),
    );

    const name = screen.getByLabelText(/^Name/);
    await userEvent.clear(name);
    await userEvent.type(name, "ClinicIT Demo Clinic, Hyderabad");
    await userEvent.click(screen.getByRole("button", { name: "Save name" }));
    await waitFor(() =>
      expect(api.callsTo("PUT", "/api/v1/clinic")[0]?.body).toEqual({ name: "ClinicIT Demo Clinic, Hyderabad" }),
    );
  });

  it("shows the server's reason when an account cannot be created", async () => {
    api.route("POST /api/v1/users", () => apiError(409, "EMAIL_TAKEN", "An account with this email already exists"));
    await open();
    const form = screen.getByRole("form", { name: "Add a staff account" });
    await userEvent.type(within(form).getByLabelText("Full name"), "Someone");
    await userEvent.type(within(form).getByLabelText("Email"), "desk@city.test");
    await userEvent.type(within(form).getByLabelText(/Initial password/), "a-long-initial-pass");
    await userEvent.click(within(form).getByRole("button", { name: "Create account" }));
    expect(await screen.findByRole("alert")).toHaveTextContent("An account with this email already exists");
  });
});
