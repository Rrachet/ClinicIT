import { screen, waitFor } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import type { PublicCheckIn } from "@/api/types";
import { apiError, fakeApi } from "@/test/fakeApi";
import { navigationMock, router } from "@/test/navigation";
import { renderWithAuth } from "@/test/render";
import { CheckInScreen } from "./CheckInScreen";

vi.mock("next/navigation", () => navigationMock);

const CODE = "clinic0123456789";
const PAGE: PublicCheckIn = {
  clinicName: "City Clinic",
  open: true,
  visitTypes: [
    { id: "vt-child", name: "Child (paediatrics)", description: "Under 16", available: true },
    { id: "vt-skin", name: "Skin", description: null, available: false },
  ],
};

describe("CheckInScreen", () => {
  let api: ReturnType<typeof fakeApi>;
  beforeEach(() => {
    router.push.mockReset();
    api = fakeApi().route(`GET /api/v1/public/check-in/${CODE}`, () => ({ body: PAGE }));
    vi.stubGlobal("fetch", api.fetchMock);
  });
  afterEach(() => vi.unstubAllGlobals());

  async function fill() {
    await userEvent.type(await screen.findByLabelText(/Your name/), "Asha Rao");
    await userEvent.type(screen.getByLabelText("Phone number"), "98765 43210");
  }

  it("checks a patient in without any login and opens their place in the queue", async () => {
    api.route(`POST /api/v1/public/check-in/${CODE}`, () => ({
      status: 201,
      body: { statusCode: "status-code-1234567", tokenNumber: 4, doctorName: "Dr. Iyer", room: "Room 2", alreadyCheckedIn: false },
    }));
    renderWithAuth(<CheckInScreen code={CODE} />);
    expect(await screen.findByText("City Clinic")).toBeInTheDocument();
    expect(screen.getByRole("note")).toHaveTextContent("Not for emergencies");
    await fill();
    expect(screen.getByRole("radio", { name: /Skin/ })).toBeDisabled();
    await userEvent.click(screen.getByRole("radio", { name: /Child/ }));
    await userEvent.type(screen.getByLabelText(/Anything the doctor should know/), "Fever since yesterday");
    await userEvent.click(screen.getByRole("button", { name: "Check in" }));

    await waitFor(() => expect(router.push).toHaveBeenCalledWith("/status/status-code-1234567?checkedIn=1"));
    const [call] = api.callsTo("POST", `/api/v1/public/check-in/${CODE}`);
    expect(call.body).toEqual({ fullName: "Asha Rao", phone: "98765 43210", visitTypeId: "vt-child", reason: "Fever since yesterday" });
    expect(call.authorization).toBeUndefined();
  });

  it("asks for a visit type before sending anything", async () => {
    renderWithAuth(<CheckInScreen code={CODE} />);
    await fill();
    await userEvent.click(screen.getByRole("button", { name: "Check in" }));
    expect(await screen.findByRole("alert")).toHaveTextContent("Please choose what you are here for.");
    expect(api.callsTo("POST", `/api/v1/public/check-in/${CODE}`)).toHaveLength(0);
  });

  it("explains when the doctor became unavailable, and refreshes the options", async () => {
    api.route(`POST /api/v1/public/check-in/${CODE}`, () => apiError(409, "NO_DOCTOR_AVAILABLE", "No doctor"));
    renderWithAuth(<CheckInScreen code={CODE} />);
    await fill();
    await userEvent.click(screen.getByRole("radio", { name: /Child/ }));
    await userEvent.click(screen.getByRole("button", { name: "Check in" }));
    expect(await screen.findByRole("alert")).toHaveTextContent("No doctor for this visit is available right now");
    await waitFor(() => expect(api.callsTo("GET", `/api/v1/public/check-in/${CODE}`).length).toBeGreaterThan(1));
    expect(router.push).not.toHaveBeenCalled();
  });

  it("asks patients to wait when no doctor is live", async () => {
    api.route(`GET /api/v1/public/check-in/${CODE}`, () => ({
      body: { ...PAGE, open: false, visitTypes: PAGE.visitTypes.map((t) => ({ ...t, available: false })) },
    }));
    renderWithAuth(<CheckInScreen code={CODE} />);
    expect(await screen.findByText("No doctor is seeing patients right now.")).toBeInTheDocument();
    expect(screen.queryByRole("button", { name: "Check in" })).not.toBeInTheDocument();
  });

  it("says when the code is not a clinic's", async () => {
    api.route(`GET /api/v1/public/check-in/${CODE}`, () => apiError(404, "NOT_FOUND", "Clinic not found"));
    renderWithAuth(<CheckInScreen code={CODE} />);
    expect(await screen.findByRole("heading", { name: "Check-in code not valid" })).toBeInTheDocument();
  });
});
