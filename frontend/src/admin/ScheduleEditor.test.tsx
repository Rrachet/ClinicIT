import { screen, waitFor, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import type { DoctorSchedule } from "@/api/types";
import { apiError, fakeApi } from "@/test/fakeApi";
import { CLINIC, MEHTA, session, SHARMA } from "@/test/fixtures";
import { navigationMock } from "@/test/navigation";
import { renderWithAuth } from "@/test/render";
import { ScheduleEditor } from "./ScheduleEditor";

vi.mock("next/navigation", () => navigationMock);

describe("ScheduleEditor", () => {
  let api: ReturnType<typeof fakeApi>;
  let sharma: DoctorSchedule;

  beforeEach(() => {
    sharma = {
      doctorId: SHARMA.id,
      appointmentMinutes: 15,
      weeklyHours: [{ dayOfWeek: "MONDAY", start: "09:00:00", end: "17:00:00", breakStart: "13:00:00", breakEnd: "14:00:00" }],
      timeOff: [{ id: "off-1", startsAt: "2026-03-16T00:00:00", endsAt: "2026-03-17T00:00:00", reason: "Conference" }],
    };
    api = fakeApi()
      .route("GET /api/v1/clinic", () => ({ body: CLINIC }))
      .route("GET /api/v1/doctors", () => ({ body: [SHARMA, MEHTA] }))
      .route(`GET /api/v1/doctors/${SHARMA.id}/schedule`, () => ({ body: sharma }))
      .route(`GET /api/v1/doctors/${MEHTA.id}/schedule`, () => ({
        body: { doctorId: MEHTA.id, appointmentMinutes: 15, weeklyHours: [], timeOff: [] },
      }))
      .route(`PUT /api/v1/doctors/${SHARMA.id}/schedule`, ({ body }) => ({ body: { ...sharma, ...(body as object) } }));
    vi.stubGlobal("fetch", api.fetchMock);
  });
  afterEach(() => vi.unstubAllGlobals());

  async function open() {
    renderWithAuth(<ScheduleEditor />, { session: session("ADMIN") });
    await screen.findByRole("heading", { name: "Weekly hours" });
  }

  it("shows the saved week and leave, and saves an edited week", async () => {
    await open();
    expect(screen.getByLabelText("Monday: works")).toBeChecked();
    expect(screen.getByLabelText("Monday: break from")).toHaveValue("13:00");
    expect(screen.getByLabelText("Tuesday: works")).not.toBeChecked();
    expect(within(screen.getByRole("list", { name: "Planned leave" })).getByText("2026-03-16")).toBeInTheDocument();

    await userEvent.click(screen.getByLabelText("Tuesday: works"));
    await userEvent.selectOptions(screen.getByRole("combobox", { name: "Appointment length" }), "20");
    await userEvent.click(screen.getByRole("button", { name: "Save weekly hours" }));

    await waitFor(() => expect(api.callsTo("PUT", `/api/v1/doctors/${SHARMA.id}/schedule`)).toHaveLength(1));
    expect(api.callsTo("PUT", `/api/v1/doctors/${SHARMA.id}/schedule`)[0].body).toEqual({
      appointmentMinutes: 20,
      weeklyHours: [
        { dayOfWeek: "MONDAY", start: "09:00", end: "17:00", breakStart: "13:00", breakEnd: "14:00" },
        { dayOfWeek: "TUESDAY", start: "09:00", end: "17:00", breakStart: null, breakEnd: null },
      ],
    });
    expect(await screen.findByText("Schedule saved.")).toBeInTheDocument();
  });

  it("shows the backend's reason when a week is invalid", async () => {
    api.route(`PUT /api/v1/doctors/${SHARMA.id}/schedule`, () =>
      apiError(400, "INVALID_SCHEDULE", "MONDAY: the break must lie inside the working hours"),
    );
    await open();
    await userEvent.click(screen.getByRole("button", { name: "Save weekly hours" }));
    expect(await screen.findByRole("alert")).toHaveTextContent("the break must lie inside the working hours");
  });

  it("adds whole days of leave and says how many bookings need moving", async () => {
    api.route(`POST /api/v1/doctors/${SHARMA.id}/time-off`, ({ body }) => ({
      status: 201,
      body: { timeOff: { id: "off-2", ...(body as object), reason: null }, bookedAppointments: 2 },
    }));
    await open();
    await userEvent.clear(screen.getByLabelText("First day"));
    await userEvent.type(screen.getByLabelText("First day"), "2026-03-20");
    await userEvent.clear(screen.getByLabelText("Last day"));
    await userEvent.type(screen.getByLabelText("Last day"), "2026-03-21");
    await userEvent.click(screen.getByRole("button", { name: "Add leave" }));

    await waitFor(() => expect(api.callsTo("POST", `/api/v1/doctors/${SHARMA.id}/time-off`)).toHaveLength(1));
    expect(api.callsTo("POST", `/api/v1/doctors/${SHARMA.id}/time-off`)[0].body).toEqual({
      startsAt: "2026-03-20T00:00:00",
      endsAt: "2026-03-22T00:00:00",
    });
    expect(await screen.findByText(/2 booked appointment\(s\) fall in it/)).toBeInTheDocument();
  });

  it("loads each doctor's own schedule", async () => {
    await open();
    await userEvent.selectOptions(screen.getByRole("combobox", { name: "Doctor" }), MEHTA.id);
    await waitFor(() => expect(screen.getByLabelText("Monday: works")).not.toBeChecked());
    expect(screen.getByText("No leave planned.")).toBeInTheDocument();
  });
});
