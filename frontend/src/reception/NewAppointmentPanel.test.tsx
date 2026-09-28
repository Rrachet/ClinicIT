import { render, screen, waitFor } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { beforeEach, describe, expect, it, type Mock, vi } from "vitest";
import { createApiClient } from "@/api/client";
import { clinicApi } from "@/api/clinicApi";
import type { Availability, Patient } from "@/api/types";
import { apiError, fakeApi } from "@/test/fakeApi";
import { appointment, CLINIC, MEHTA, SHARMA } from "@/test/fixtures";
import { API } from "@/test/render";
import { NewAppointmentPanel } from "./NewAppointmentPanel";
import type { BookingResult } from "./workflows";

const ASHA: Patient = { id: "p-asha", clinicId: CLINIC.id, fullName: "Asha Rao", phone: "+91 90000 00000", dateOfBirth: null };

function day(doctorId: string, slots: [string, boolean][]): Availability {
  return {
    doctorId,
    date: CLINIC.today,
    scheduled: true,
    appointmentMinutes: 15,
    hours: { start: "09:00:00", end: "17:00:00", breakStart: null, breakEnd: null },
    timeOff: [],
    slots: slots.map(([time, available]) => ({
      start: `${CLINIC.today}T${time}:00`,
      end: `${CLINIC.today}T${time}:00`,
      available,
      reason: available ? null : "SLOT_TAKEN",
    })),
  };
}

describe("NewAppointmentPanel", () => {
  let api: ReturnType<typeof fakeApi>;
  let onBooked: Mock<(result: BookingResult) => void>;

  beforeEach(() => {
    api = fakeApi()
      .route("GET /api/v1/patients", () => ({ body: [ASHA] }))
      .route("POST /api/v1/appointments", ({ body }) => ({
        status: 201,
        body: appointment({ id: "new", patientName: "Asha Rao", ...(body as object) }),
      }))
      .route("POST /api/v1/appointments/new/confirm", () => ({ body: appointment({ id: "new", status: "CONFIRMED" }) }))
      .route("POST /api/v1/appointments/new/arrive", () => ({ body: appointment({ id: "new", status: "ARRIVED" }) }))
      .route("POST /api/v1/queue-entries", () => ({ status: 201, body: { id: "q1", tokenNumber: 4, statusCode: "code" } }))
      .route(`GET /api/v1/doctors/${SHARMA.id}/availability`, () => ({
        body: day(SHARMA.id, [["11:00", false], ["11:15", true], ["11:30", true]]),
      }))
      .route(`GET /api/v1/doctors/${MEHTA.id}/availability`, () => ({
        body: { ...day(MEHTA.id, []), scheduled: false, hours: null },
      }));
    onBooked = vi.fn<(result: BookingResult) => void>();
    const http = createApiClient({ baseUrl: API, getToken: () => "token", fetchImpl: api.fetchMock });
    render(<NewAppointmentPanel api={clinicApi(http)} clinic={CLINIC} doctors={[SHARMA, MEHTA]} onBooked={onBooked} />);
  });

  async function pickAsha() {
    await userEvent.type(screen.getByLabelText("Find patient"), "As");
    await userEvent.click(await screen.findByRole("button", { name: /Asha Rao/ }));
  }

  it("books a walk-in for now without asking for a time", async () => {
    await pickAsha();
    expect(screen.queryByLabelText("Time")).not.toBeInTheDocument();
    await userEvent.click(screen.getByRole("button", { name: "Book and add to queue" }));

    await waitFor(() => expect(onBooked).toHaveBeenCalled());
    expect(api.callsTo("POST", "/api/v1/appointments")[0].body).toEqual({
      patientId: ASHA.id,
      doctorId: SHARMA.id,
      walkIn: true,
    });
    expect(api.callsTo("GET", `/api/v1/doctors/${SHARMA.id}/availability`)).toHaveLength(0);
  });

  it("offers only the doctor's free slots for a later booking", async () => {
    await pickAsha();
    await userEvent.click(screen.getByLabelText(/Patient is here now/));

    const time = await screen.findByRole("combobox", { name: "Time" });
    await waitFor(() => expect(time).not.toBeDisabled());
    expect([...(time as HTMLSelectElement).options].map((o) => o.value)).toEqual(["11:15", "11:30"]);
    await userEvent.selectOptions(time, "11:30");
    await userEvent.click(screen.getByRole("button", { name: "Book appointment" }));

    await waitFor(() => expect(onBooked).toHaveBeenCalled());
    expect(api.callsTo("POST", "/api/v1/appointments")[0].body).toEqual({
      patientId: ASHA.id,
      doctorId: SHARMA.id,
      scheduledAt: `${CLINIC.today}T11:30:00`,
    });
  });

  it("cannot book a day with no free slots", async () => {
    api.route(`GET /api/v1/doctors/${SHARMA.id}/availability`, () => ({ body: day(SHARMA.id, [["11:00", false]]) }));
    await pickAsha();
    await userEvent.click(screen.getByLabelText(/Patient is here now/));

    expect(await screen.findByRole("status")).toHaveTextContent("No free slots on this day.");
    expect(screen.getByRole("button", { name: "Book appointment" })).toBeDisabled();
  });

  it("lets any time be typed for a doctor without a schedule", async () => {
    await pickAsha();
    await userEvent.click(screen.getByLabelText(/Patient is here now/));
    await userEvent.selectOptions(screen.getByRole("combobox", { name: "Doctor" }), MEHTA.id);

    const time = await screen.findByLabelText("Time");
    expect(time).toHaveAttribute("type", "time");
    await userEvent.clear(time);
    await userEvent.type(time, "18:45");
    await userEvent.click(screen.getByRole("button", { name: "Book appointment" }));

    await waitFor(() => expect(onBooked).toHaveBeenCalled());
    expect((api.callsTo("POST", "/api/v1/appointments")[0].body as { scheduledAt: string }).scheduledAt)
      .toBe(`${CLINIC.today}T18:45:00`);
  });

  it("shows why the backend refused a slot", async () => {
    api.route("POST /api/v1/appointments", () =>
      apiError(409, "SLOT_TAKEN", "The doctor already has an appointment at this time"),
    );
    await pickAsha();
    await userEvent.click(screen.getByLabelText(/Patient is here now/));
    await waitFor(() => expect(screen.getByRole("combobox", { name: "Time" })).not.toBeDisabled());
    await userEvent.click(screen.getByRole("button", { name: "Book appointment" }));

    expect(await screen.findByRole("alert")).toHaveTextContent("The doctor already has an appointment at this time");
    expect(onBooked).not.toHaveBeenCalled();
  });
});
