import { act, screen, waitFor } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { apiError, fakeApi } from "@/test/fakeApi";
import { FakeStompClient } from "@/test/fakeStomp";
import { appointment, board, CLINIC, event, row, session, SHARMA } from "@/test/fixtures";
import { navigationMock } from "@/test/navigation";
import { renderWithAuth } from "@/test/render";
import { RequireRole } from "@/auth/RequireRole";
import { DoctorConsole } from "./DoctorConsole";

vi.mock("next/navigation", () => navigationMock);
vi.mock("@stomp/stompjs", async () => ({ Client: (await import("@/test/fakeStomp")).FakeStompClient }));

describe("DoctorConsole", () => {
  let api: ReturnType<typeof fakeApi>;

  beforeEach(() => {
    FakeStompClient.reset();
    api = fakeApi()
      .route("GET /api/v1/clinic", () => ({ body: CLINIC }))
      .route("GET /api/v1/appointments", () => ({
        body: [appointment({ id: "a", patientName: "Asha Rao", status: "WAITING", reasonSummary: "Fever", scheduledAt: `${CLINIC.today}T10:15:00` })],
      }))
      .route("GET /api/v1/queues/today", () => ({
        body: board(SHARMA.id, [row({ id: "qa", appointmentId: "a", tokenNumber: 27, patientName: "Asha Rao" })]),
      }));
    vi.stubGlobal("fetch", api.fetchMock);
  });
  afterEach(() => vi.unstubAllGlobals());

  async function open() {
    // As in app/doctor/page.tsx: the guard stops rendering the console once the session ends.
    renderWithAuth(
      <RequireRole area="doctor">
        <DoctorConsole />
      </RequireRole>,
      { session: session("DOCTOR") },
    );
    await screen.findByText("No patient with you.");
    const client = FakeStompClient.latest();
    act(() => client.connect());
    return client;
  }

  it("follows only the doctor's own topic and own board", async () => {
    const client = await open();
    expect(client.subscriptions.map((s) => s.destination)).toEqual([`/topic/clinic/${CLINIC.id}/doctor/${SHARMA.id}/queue`]);
    expect(api.callsTo("GET", "/api/v1/queues/today").every((c) => c.path.includes(`doctorId=${SHARMA.id}`))).toBe(true);
    expect(screen.getByRole("button", { name: "Call next · #27" })).toBeEnabled();
  });

  it("shows the patient as soon as reception calls them (no refresh)", async () => {
    const client = await open();
    act(() => client.emit(event({ queueEntryId: "qa", appointmentId: "a", entryVersion: 1, tokenNumber: 27, status: "CALLED" })));

    expect(screen.getByTestId("doctor-current-token")).toHaveTextContent("#27");
    expect(screen.getByText("Asha Rao", { selector: ".current-name" })).toBeInTheDocument();
    expect(await screen.findByText("Fever")).toBeInTheDocument();
    expect(screen.getByRole("button", { name: "Start consultation" })).toBeInTheDocument();
  });

  it("starts and completes through the API; the screen follows the events", async () => {
    api
      .route("POST /api/v1/queue-entries/qa/start", () => ({ body: {} }))
      .route("POST /api/v1/queue-entries/qa/complete", () => ({ body: {} }));
    const client = await open();
    act(() => client.emit(event({ queueEntryId: "qa", appointmentId: "a", entryVersion: 1, tokenNumber: 27, status: "CALLED" })));

    await userEvent.click(screen.getByRole("button", { name: "Start consultation" }));
    expect(api.callsTo("POST", "/api/v1/queue-entries/qa/start")).toHaveLength(1);
    act(() => client.emit(event({ queueEntryId: "qa", appointmentId: "a", entryVersion: 2, tokenNumber: 27, status: "IN_CONSULTATION" })));

    await userEvent.click(screen.getByRole("button", { name: "Complete consultation" }));
    act(() => client.emit(event({ queueEntryId: "qa", appointmentId: "a", entryVersion: 3, tokenNumber: 27, status: "COMPLETED" })));
    expect(await screen.findByText("No patient with you.")).toBeInTheDocument();
  });

  it("shows why an action was refused", async () => {
    api.route("POST /api/v1/queues/call-next", () => apiError(403, "FORBIDDEN", "Doctors can only access their own appointments and queue"));
    await open();
    await userEvent.click(screen.getByRole("button", { name: "Call next · #27" }));
    expect(await screen.findByRole("alert")).toHaveTextContent("You don't have permission to do that.");
  });

  it("returns to login when the server rejects the token on the socket", async () => {
    const { router } = await import("@/test/navigation");
    const client = await open();
    act(() => client.error("Authentication required"));
    await waitFor(() => expect(router.replace).toHaveBeenCalledWith("/login?reason=expired"));
    expect(sessionStorage.getItem("clinicit.session")).toBeNull();
    expect(client.active).toBe(false);
  });
});
