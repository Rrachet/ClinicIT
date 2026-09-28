import { fireEvent, screen, waitFor, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import type { DailySummary, DoctorAnalytics, QueueAnalytics, WaitTimes } from "@/api/types";
import { RequireRole } from "@/auth/RequireRole";
import { apiError, fakeApi } from "@/test/fakeApi";
import { CLINIC, MEHTA, session, SHARMA } from "@/test/fixtures";
import { router } from "@/test/navigation";
import { renderWithAuth } from "@/test/render";
import { AnalyticsDashboard } from "./AnalyticsDashboard";

vi.mock("next/navigation", async () => (await import("@/test/navigation")).navigationMock);

const SUMMARY: DailySummary = {
  date: CLINIC.today,
  timezone: CLINIC.timezone,
  doctorId: null,
  scheduledAppointments: 8,
  patients: 5,
  completedConsultations: 4,
  averageWaitSeconds: 2325,
  medianWaitSeconds: 2100,
  averageConsultationSeconds: 975,
  averageDelaySeconds: 2325,
  cancellations: 1,
  noShows: 1,
  cancellationRate: 0.125,
  noShowRate: 1 / 7,
  currentQueueLength: 1,
};

const WAITS: WaitTimes = {
  date: CLINIC.today, doctorId: null, calledPatients: 4, averageWaitSeconds: 2325, medianWaitSeconds: 2100,
  p90WaitSeconds: 3030, maxWaitSeconds: 3300,
  byHour: [
    { hour: 9, calledPatients: 2, averageWaitSeconds: 2100, medianWaitSeconds: 2100 },
    { hour: 10, calledPatients: 2, averageWaitSeconds: 2550, medianWaitSeconds: 2550 },
  ],
};

const QUEUE: QueueAnalytics = {
  date: CLINIC.today, doctorId: null, currentQueueLength: 1,
  byHour: [
    ...Array.from({ length: 9 }, (_, hour) => ({ hour, joined: 0, completed: 0, queueLength: 0 })),
    { hour: 9, joined: 3, completed: 1, queueLength: 1 },
    { hour: 10, joined: 2, completed: 3, queueLength: 1 },
    { hour: 11, joined: 0, completed: 0, queueLength: 1 },
  ],
};

const DOCTORS: DoctorAnalytics = {
  date: CLINIC.today,
  doctors: [
    { doctorId: MEHTA.id, doctorName: "Dr. Mehta", patientsCalled: 1, patientsHandled: 1, averageWaitSeconds: 1800,
      averageConsultationSeconds: 1200, consultationSeconds: 1200, utilization: 1 },
    { doctorId: SHARMA.id, doctorName: "Dr. Sharma", patientsCalled: 3, patientsHandled: 3, averageWaitSeconds: 2500,
      averageConsultationSeconds: 900, consultationSeconds: 2700, utilization: 2700 / 3900,
      scheduledMinutes: 420, scheduledUtilization: 2700 / (420 * 60) },
  ],
};

describe("AnalyticsDashboard", () => {
  let api: ReturnType<typeof fakeApi>;

  beforeEach(() => {
    router.replace.mockClear();
    api = fakeApi()
      .route("GET /api/v1/clinic", () => ({ body: CLINIC }))
      .route("GET /api/v1/doctors", () => ({ body: [MEHTA, SHARMA] }))
      .route("GET /api/v1/analytics/today", () => ({ body: SUMMARY }))
      .route("GET /api/v1/analytics/wait-times", () => ({ body: WAITS }))
      .route("GET /api/v1/analytics/queue", () => ({ body: QUEUE }))
      .route("GET /api/v1/analytics/doctors", () => ({ body: DOCTORS }));
    vi.stubGlobal("fetch", api.fetchMock);
  });
  afterEach(() => vi.unstubAllGlobals());

  function open(role: "ADMIN" | "RECEPTIONIST" = "ADMIN") {
    return renderWithAuth(
      <RequireRole area="admin">
        <AnalyticsDashboard />
      </RequireRole>,
      { session: session(role) },
    );
  }

  it("shows today's figures as the backend computed them", async () => {
    open();
    const today = await screen.findByRole("region", { name: "Today" });
    const kpi = (label: string) => within(today).getByText(label).parentElement!;

    expect(kpi("Patients")).toHaveTextContent("5checked in · 8 booked");
    expect(kpi("Completed")).toHaveTextContent("4");
    expect(kpi("Average wait")).toHaveTextContent("39 min");
    expect(kpi("Median wait")).toHaveTextContent("35 min");
    expect(kpi("No-show rate")).toHaveTextContent("14%1 of 7 expected");
    expect(kpi("Avg consultation")).toHaveTextContent("16 min");
    expect(today).toHaveTextContent("Cancelled: 1 (13%)");
    // The clinic's day, asked for explicitly: never the browser's date.
    expect(api.callsTo("GET", "/api/v1/analytics/today")[0].path).toContain(`date=${CLINIC.today}`);
  });

  it("lists every doctor with patients seen, waits and utilization", async () => {
    open();
    const table = await screen.findByRole("table", { name: "Doctors" });
    const sharma = within(table).getByRole("row", { name: /Dr. Sharma/ });
    expect(sharma).toHaveTextContent("Dr. Sharma342 min15 min69%11% of 7 h 00 min");
    const mehta = within(table).getByRole("row", { name: /Dr. Mehta/ });
    expect(mehta).toHaveTextContent("100%");
    expect(mehta).toHaveTextContent("No schedule");
  });

  it("shows the queue now and hour by hour from the first busy hour", async () => {
    open();
    expect(await screen.findByTestId("current-queue-length")).toHaveTextContent("1");
    const hours = screen.getByRole("table", { name: "Queue by hour" });
    const rows = within(hours).getAllByRole("row").slice(1);
    expect(rows.map((r) => within(r).getByRole("rowheader").textContent)).toEqual(["09:00", "10:00", "11:00"]);
    // 10:00 · checked in 2 · completed 3 · 1 waiting at the end · those called waited 43 min on average
    expect(rows[1]).toHaveTextContent("10:00231" + "43 min");
    expect(rows[2]).toHaveTextContent("—");
  });

  it("asks for another day or one doctor when chosen", async () => {
    const user = userEvent.setup();
    open();
    await screen.findByRole("region", { name: "Today" });

    await user.selectOptions(screen.getByRole("combobox", { name: "Doctor" }), SHARMA.id);
    await waitFor(() =>
      expect(api.callsTo("GET", "/api/v1/analytics/today").at(-1)!.path).toContain(`doctorId=${SHARMA.id}`),
    );
    expect(api.callsTo("GET", "/api/v1/analytics/queue").at(-1)!.path).toContain(`doctorId=${SHARMA.id}`);

    // jsdom has no date picker to type into; set the value as the picker would.
    fireEvent.change(screen.getByLabelText("Day"), { target: { value: "2026-03-09" } });
    await waitFor(() =>
      expect(api.callsTo("GET", "/api/v1/analytics/today").at(-1)!.path).toContain("date=2026-03-09"),
    );
  });

  it("shows no data as a dash, not zero minutes", async () => {
    api.route("GET /api/v1/analytics/today", () => ({
      body: { ...SUMMARY, patients: 0, completedConsultations: 0, averageWaitSeconds: null, medianWaitSeconds: null,
        averageConsultationSeconds: null, noShowRate: null, currentQueueLength: 0 },
    }));
    api.route("GET /api/v1/analytics/queue", () => ({ body: { ...QUEUE, byHour: QUEUE.byHour.slice(0, 9) } }));
    open();
    const today = await screen.findByRole("region", { name: "Today" });
    expect(within(today).getByText("Median wait").parentElement).toHaveTextContent("—");
    expect(screen.getByText("No queue activity")).toBeInTheDocument();
  });

  it("offers a retry when the figures cannot be loaded", async () => {
    api.route("GET /api/v1/analytics/today", () => apiError(500, "INTERNAL_ERROR", "boom"));
    open();
    expect(await screen.findByRole("alert")).toHaveTextContent("Something went wrong on the server");
    expect(screen.getByRole("button", { name: "Retry" })).toBeInTheDocument();
  });

  it("is for admins only", async () => {
    open("RECEPTIONIST");
    await waitFor(() => expect(router.replace).toHaveBeenCalledWith("/reception"));
    expect(api.callsTo("GET", "/api/v1/analytics/today")).toHaveLength(0);
  });
});
