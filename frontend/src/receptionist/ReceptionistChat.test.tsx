import { screen, waitFor } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { apiError, fakeApi } from "@/test/fakeApi";
import { navigationMock } from "@/test/navigation";
import { renderWithAuth } from "@/test/render";
import { ReceptionistChat } from "./ReceptionistChat";

vi.mock("next/navigation", () => navigationMock);

const CODE = "clinic0123456789";
const INFO = `GET /api/v1/public/receptionist/${CODE}`;
const SEND = `POST /api/v1/public/receptionist/${CODE}/messages`;

describe("ReceptionistChat", () => {
  let api: ReturnType<typeof fakeApi>;
  beforeEach(() => {
    api = fakeApi().route(INFO, () => ({ body: { clinicName: "City Clinic", enabled: true, greeting: "Hello! At the clinic now?" } }));
    vi.stubGlobal("fetch", api.fetchMock);
  });
  afterEach(() => vi.unstubAllGlobals());

  it("chats without a login, then hands the patient their place in the queue", async () => {
    let turn = 0;
    api.route(SEND, () => {
      turn += 1;
      return turn === 1
        ? { body: { conversationId: "conv-1", reply: "Your name and phone, please?", actions: [] } }
        : {
            body: {
              conversationId: "conv-1",
              reply: "You're checked in.",
              actions: [{ type: "QUEUED", statusCode: "status-123456789012", tokenNumber: 7, doctorName: "Dr. Iyer", room: "Room 2", startsAt: null }],
            },
          };
    });
    renderWithAuth(<ReceptionistChat code={CODE} />);
    expect(await screen.findByText("Hello! At the clinic now?")).toBeInTheDocument();
    expect(screen.getByRole("note")).toHaveTextContent("Not for emergencies");

    await userEvent.click(screen.getByRole("button", { name: "I'm at the clinic now" }));
    expect(await screen.findByText("Your name and phone, please?")).toBeInTheDocument();
    expect(api.callsTo("POST", `/api/v1/public/receptionist/${CODE}/messages`)[0].body).toEqual({ message: "I'm at the clinic now" });
    expect(api.callsTo("POST", `/api/v1/public/receptionist/${CODE}/messages`)[0].authorization).toBeUndefined();

    await userEvent.type(screen.getByLabelText("Message"), "Meera Nair, 90000 00031");
    await userEvent.click(screen.getByRole("button", { name: "Send" }));
    expect(await screen.findByText("You're checked in.")).toBeInTheDocument();
    expect(api.callsTo("POST", `/api/v1/public/receptionist/${CODE}/messages`)[1].body).toEqual({
      conversationId: "conv-1",
      message: "Meera Nair, 90000 00031",
    });
    expect(screen.getByText("#7")).toBeInTheDocument();
    expect(screen.getByRole("link", { name: "Open my place in the queue" })).toHaveAttribute("href", "/status/status-123456789012?checkedIn=1");
  });

  it("shows a booking with its day and time", async () => {
    api.route(SEND, () => ({
      body: {
        conversationId: "conv-2",
        reply: "Booked!",
        actions: [{ type: "BOOKED", statusCode: null, tokenNumber: null, doctorName: "Dr. Iyer", room: "Room 2", startsAt: "2026-03-11T10:00:00" }],
      },
    }));
    renderWithAuth(<ReceptionistChat code={CODE} />);
    await userEvent.click(await screen.findByRole("button", { name: "I'd like to book an appointment" }));
    expect(await screen.findByText("Appointment booked")).toBeInTheDocument();
    expect(screen.getByText(/Wednesday.*11.* at 10:00/)).toBeInTheDocument();
    expect(screen.getByText(/scan the clinic's QR code again/)).toBeInTheDocument();
  });

  it("keeps the message and points to the form when the receptionist is unavailable", async () => {
    api.route(SEND, () => apiError(503, "RECEPTIONIST_UNAVAILABLE", "busy"));
    renderWithAuth(<ReceptionistChat code={CODE} />);
    await userEvent.type(await screen.findByLabelText("Message"), "hello there");
    await userEvent.click(screen.getByRole("button", { name: "Send" }));
    expect(await screen.findByRole("alert")).toHaveTextContent("use the check-in form");
    await waitFor(() => expect(screen.getByLabelText("Message")).toHaveValue("hello there"));
    expect(screen.queryByText("hello there", { selector: "p" })).not.toBeInTheDocument();
    expect(screen.getByRole("link", { name: "Check in here" })).toHaveAttribute("href", `/check-in/${CODE}`);
  });

  it("offers the form when the clinic has no AI receptionist", async () => {
    api.route(INFO, () => ({ body: { clinicName: "City Clinic", enabled: false, greeting: "" } }));
    renderWithAuth(<ReceptionistChat code={CODE} />);
    expect(await screen.findByRole("link", { name: "Open the check-in form" })).toHaveAttribute("href", `/check-in/${CODE}`);
    expect(screen.queryByLabelText("Message")).not.toBeInTheDocument();
  });
});
