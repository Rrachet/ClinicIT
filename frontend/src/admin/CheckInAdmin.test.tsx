import { screen, waitFor, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import type { VisitType } from "@/api/types";
import { fakeApi } from "@/test/fakeApi";
import { CLINIC, MEHTA, SHARMA, session } from "@/test/fixtures";
import { navigationMock } from "@/test/navigation";
import { renderWithAuth } from "@/test/render";
import { CheckInAdmin } from "./CheckInAdmin";

vi.mock("next/navigation", () => navigationMock);

describe("CheckInAdmin", () => {
  let api: ReturnType<typeof fakeApi>;
  let visitTypes: VisitType[];
  beforeEach(() => {
    visitTypes = [];
    api = fakeApi()
      .route("GET /api/v1/clinic", () => ({ body: { ...CLINIC, checkInCode: "abc123def456" } }))
      .route("GET /api/v1/doctors", () => ({
        body: [
          { ...SHARMA, specialization: "Paediatrics", live: true, room: "Room 1" },
          { ...MEHTA, live: false, room: null },
        ],
      }))
      .route("GET /api/v1/visit-types", () => ({ body: visitTypes }));
    vi.stubGlobal("fetch", api.fetchMock);
  });
  afterEach(() => vi.unstubAllGlobals());

  it("shows the QR code and link patients scan", async () => {
    renderWithAuth(<CheckInAdmin />, { session: session("ADMIN") });
    expect(await screen.findByRole("heading", { name: "Scan to check in" })).toBeInTheDocument();
    expect(screen.getByLabelText("Check-in link")).toHaveValue("http://localhost:3000/check-in/abc123def456");
    await waitFor(() => expect(screen.getByRole("img", { name: "Check-in QR code" }).querySelector("svg")).not.toBeNull());
    expect(screen.getByText("Add at least one visit type below.", { exact: false })).toBeInTheDocument();
  });

  it("adds a visit type seen by chosen doctors", async () => {
    api.route("POST /api/v1/visit-types", ({ body }) => {
      const created = { id: "vt1", sortOrder: 0, active: true, description: null, ...(body as object) } as VisitType;
      visitTypes = [created];
      return { status: 201, body: created };
    });
    renderWithAuth(<CheckInAdmin />, { session: session("ADMIN") });
    const form = await screen.findByRole("form", { name: "Add a visit type" });
    await userEvent.type(within(form).getByLabelText("Name patients see"), "Child (paediatrics)");
    await userEvent.click(within(form).getByRole("checkbox", { name: /Dr. Sharma/ }));
    await userEvent.click(within(form).getByRole("button", { name: "Add visit type" }));

    expect(await screen.findByText('Saved "Child (paediatrics)".')).toBeInTheDocument();
    expect(api.callsTo("POST", "/api/v1/visit-types")[0].body).toEqual({
      name: "Child (paediatrics)",
      anyDoctor: false,
      doctorIds: [SHARMA.id],
    });
    expect(await screen.findByRole("rowheader", { name: /Child \(paediatrics\)/ })).toBeInTheDocument();
  });

  it("can take a doctor who forgot offline", async () => {
    api.route(`PUT /api/v1/doctors/${SHARMA.id}/duty`, () => ({ body: { ...SHARMA, live: false } }));
    renderWithAuth(<CheckInAdmin />, { session: session("ADMIN") });
    await userEvent.click(await screen.findByRole("button", { name: "Take Dr. Sharma offline" }));
    await waitFor(() => expect(api.callsTo("PUT", `/api/v1/doctors/${SHARMA.id}/duty`)[0].body).toEqual({ live: false }));
  });
});
