import { act, screen, waitFor, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import type { Appointment } from "@/api/types";
import { apiError, fakeApi } from "@/test/fakeApi";
import { FakeStompClient } from "@/test/fakeStomp";
import { appointment, board, CLINIC, event, MEHTA, row, session, SHARMA } from "@/test/fixtures";
import { navigationMock } from "@/test/navigation";
import { renderWithAuth } from "@/test/render";
import { ReceptionConsole } from "./ReceptionConsole";

vi.mock("next/navigation", () => navigationMock);
vi.mock("@stomp/stompjs", async () => ({ Client: (await import("@/test/fakeStomp")).FakeStompClient }));

describe("ReceptionConsole", () => {
  let api: ReturnType<typeof fakeApi>;
  let appointments: Appointment[];

  beforeEach(() => {
    FakeStompClient.reset();
    appointments = [
      appointment({ id: "booked", patientName: "Booked Person", status: "BOOKED", scheduledAt: `${CLINIC.today}T09:30:00` }),
      appointment({ id: "a", patientName: "Asha Rao", status: "WAITING" }),
      appointment({ id: "b", patientName: "Vikram Singh", status: "WAITING", doctorId: SHARMA.id }),
    ];
    api = fakeApi()
      .route("GET /api/v1/clinic", () => ({ body: CLINIC }))
      .route("GET /api/v1/doctors", () => ({ body: [SHARMA, MEHTA] }))
      .route("GET /api/v1/appointments", () => ({ body: appointments }));
    // Boards differ per doctor, so they are served here by doctorId.
    vi.stubGlobal("fetch", async (input: RequestInfo | URL, init?: RequestInit) => {
      const url = new URL(String(input));
      if (url.pathname === "/api/v1/queues/today") {
        const doctorId = url.searchParams.get("doctorId");
        api.calls.push({ method: "GET", path: url.pathname + url.search, body: undefined, authorization: undefined });
        const body =
          doctorId === SHARMA.id
            ? board(SHARMA.id, [
                row({ id: "qa", appointmentId: "a", tokenNumber: 7, patientName: "Asha Rao" }),
                row({ id: "qb", appointmentId: "b", tokenNumber: 8, patientName: "Vikram Singh" }),
              ])
            : board(MEHTA.id, []);
        return new Response(JSON.stringify(body), { status: 200 });
      }
      return api.fetchMock(input, init);
    });
  });
  afterEach(() => vi.unstubAllGlobals());

  async function open() {
    renderWithAuth(<ReceptionConsole />, { session: session("RECEPTIONIST") });
    await screen.findByText("Today's Clinic");
    await waitFor(() => expect(screen.getByRole("article", { name: "Queue for Dr. Sharma" })).toHaveTextContent("#7"));
    return FakeStompClient.latest();
  }

  it("renders today's appointments and each doctor's queue with large tokens", async () => {
    await open();
    const sharma = screen.getByRole("article", { name: "Queue for Dr. Sharma" });
    expect(within(sharma).getByText("2 waiting")).toBeInTheDocument();
    expect(within(sharma).getByRole("button", { name: "Call #7" })).toBeEnabled();
    expect(within(screen.getByRole("article", { name: "Queue for Dr. Mehta" })).getByText("Queue empty")).toBeInTheDocument();

    const rows = screen.getAllByRole("row");
    expect(rows[1]).toHaveTextContent("09:30");
    expect(rows[1]).toHaveTextContent("Booked Person");
    expect(within(rows[2]).getByText("#7")).toBeInTheDocument();
    expect(api.callsTo("GET", "/api/v1/appointments")[0].path).toContain(`date=${CLINIC.today}`);
  });

  it("subscribes to its own clinic's topic, never one chosen elsewhere", async () => {
    const client = await open();
    act(() => client.connect());
    expect(client.subscriptions.map((s) => s.destination)).toEqual([`/topic/clinic/${CLINIC.id}/queue`]);
  });

  it("updates the queue live when someone else calls a patient", async () => {
    const client = await open();
    act(() => client.connect());
    act(() => client.emit(event({ queueEntryId: "qa", appointmentId: "a", entryVersion: 1, tokenNumber: 7, status: "CALLED" })));

    expect(screen.getByTestId(`current-token-${SHARMA.id}`)).toHaveTextContent("#7");
    expect(screen.getByRole("article", { name: "Queue for Dr. Sharma" })).toHaveTextContent("Asha Rao · Called");
  });

  it("calls the next patient through the API", async () => {
    api.route("POST /api/v1/queues/call-next", ({ body }) => ({
      body: { id: "qa", tokenNumber: 7, doctorId: (body as { doctorId: string }).doctorId, status: "CALLED", version: 1, statusCode: "x" },
    }));
    await open();
    await userEvent.click(screen.getByRole("button", { name: "Call #7" }));
    expect(api.callsTo("POST", "/api/v1/queues/call-next")[0].body).toEqual({ doctorId: SHARMA.id });
    expect(await screen.findByText("Called token #7 for Dr. Sharma")).toBeInTheDocument();
  });

  it("shows the backend's reason when an action is refused", async () => {
    api.route("POST /api/v1/queues/call-next", () => apiError(409, "DOCTOR_BUSY", "Doctor already has a patient called or in consultation"));
    await open();
    await userEvent.click(screen.getByRole("button", { name: "Call #7" }));
    expect(await screen.findByRole("alert")).toHaveTextContent("Doctor already has a patient called or in consultation");
  });

  it("asks for confirmation before cancelling, and can be backed out of", async () => {
    api.route("POST /api/v1/appointments/booked/cancel", () => ({ body: { ...appointments[0], status: "CANCELLED" } }));
    await open();

    await userEvent.click(screen.getByRole("button", { name: "Cancel: Booked Person" }));
    const dialog = screen.getByRole("alertdialog", { name: "Cancel appointment?" });
    await userEvent.click(within(dialog).getByRole("button", { name: "Keep it" }));
    expect(api.callsTo("POST", "/api/v1/appointments/booked/cancel")).toHaveLength(0);

    await userEvent.click(screen.getByRole("button", { name: "Cancel: Booked Person" }));
    await userEvent.click(within(screen.getByRole("alertdialog")).getByRole("button", { name: "Cancel appointment" }));
    await waitFor(() => expect(api.callsTo("POST", "/api/v1/appointments/booked/cancel")).toHaveLength(1));
  });

  it("confirms without a dialog (not destructive)", async () => {
    api.route("POST /api/v1/appointments/booked/confirm", () => ({ body: { ...appointments[0], status: "CONFIRMED" } }));
    await open();
    await userEvent.click(screen.getByRole("button", { name: "Confirm: Booked Person" }));
    await waitFor(() => expect(api.callsTo("POST", "/api/v1/appointments/booked/confirm")).toHaveLength(1));
    expect(screen.queryByRole("alertdialog")).not.toBeInTheDocument();
  });

  it("explains a forbidden action instead of failing silently", async () => {
    api.route("POST /api/v1/appointments/booked/confirm", () => apiError(403, "FORBIDDEN", "Access denied"));
    await open();
    await userEvent.click(screen.getByRole("button", { name: "Confirm: Booked Person" }));
    expect(await screen.findByRole("alert")).toHaveTextContent("You don't have permission to do that.");
  });

  it("shows a retryable error when the clinic cannot be loaded", async () => {
    api.route("GET /api/v1/clinic", () => ({ status: 503, body: { status: 503, code: "INTERNAL_ERROR", message: "x" } }));
    renderWithAuth(<ReceptionConsole />, { session: session("RECEPTIONIST") });
    const alert = await screen.findByRole("alert");
    expect(alert).toHaveTextContent("Something went wrong on the server");
    expect(within(alert).getByRole("button", { name: "Retry" })).toBeInTheDocument();
  });
});
