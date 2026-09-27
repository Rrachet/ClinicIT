import { screen, waitFor, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import type { PatientNotification } from "@/api/types";
import { useAuth } from "@/auth/AuthProvider";
import { apiError, fakeApi } from "@/test/fakeApi";
import { session } from "@/test/fixtures";
import { renderWithAuth } from "@/test/render";
import { StatusLinkDialog } from "./StatusLinkDialog";

vi.mock("next/navigation", async () => (await import("@/test/navigation")).navigationMock);

function message(overrides: Partial<PatientNotification>): PatientNotification {
  return {
    id: "n1",
    appointmentId: "appt-1",
    type: "PATIENT_JOINED_QUEUE",
    channel: "SMS",
    recipient: "•••3210",
    body: "City Clinic: you're checked in. Your token is #24. Follow your place in the queue: http://localhost:3000/status/abc",
    status: "SENT",
    attempts: 1,
    maxAttempts: 5,
    lastError: null,
    createdAt: "2026-03-10T05:30:00Z",
    sentAt: "2026-03-10T05:30:01Z",
    nextAttemptAt: null,
    expiresAt: "2026-03-10T18:30:00Z",
    ...overrides,
  };
}

function Harness() {
  const { api } = useAuth();
  return <StatusLinkDialog api={api} link={{ appointmentId: "appt-1", code: "abc", token: 24 }} onClose={() => undefined} />;
}

describe("StatusLinkDialog", () => {
  let api: ReturnType<typeof fakeApi>;
  let messages: PatientNotification[];

  beforeEach(() => {
    messages = [
      message({}),
      message({ id: "n2", type: "PATIENT_CALLED", status: "FAILED", attempts: 5, lastError: "SIMULATED_OUTAGE", body: "City Clinic: token #24, it's your turn. Please go in now." }),
    ];
    api = fakeApi()
      .route("GET /api/v1/notifications", () => ({ body: messages }))
      .route("POST /api/v1/notifications/n2/retry", () => {
        messages = [messages[0], { ...messages[1], status: "SENT", lastError: null }];
        return { body: messages[1] };
      });
    vi.stubGlobal("fetch", api.fetchMock);
  });
  afterEach(() => vi.unstubAllGlobals());

  it("shows what was sent, to a masked number, with its status", async () => {
    renderWithAuth(<Harness />, { session: session("RECEPTIONIST") });
    const list = await screen.findByRole("list", { name: "Messages sent to the patient" });
    const queueLink = within(list).getAllByRole("listitem")[0];
    expect(queueLink).toHaveTextContent("Queue link");
    expect(queueLink).toHaveTextContent("SMS to •••3210");
    expect(queueLink).toHaveTextContent("Sent");
    expect(api.callsTo("GET", "/api/v1/notifications")[0].path).toContain("appointmentId=appt-1");
    expect(screen.getByDisplayValue(/\/status\/abc$/)).toBeInTheDocument();
  });

  it("retries a failed message and shows the new status", async () => {
    renderWithAuth(<Harness />, { session: session("RECEPTIONIST") });
    const failed = (await screen.findAllByRole("listitem"))[1];
    expect(failed).toHaveTextContent("Attempt 5 of 5 failed (SIMULATED_OUTAGE)");

    await userEvent.click(within(failed).getByRole("button", { name: "Retry" }));

    await waitFor(() => expect(screen.getAllByRole("listitem")[1]).toHaveTextContent("Sent"));
    expect(api.callsTo("POST", "/api/v1/notifications/n2/retry")).toHaveLength(1);
  });

  it("explains a refused retry", async () => {
    api.route("POST /api/v1/notifications/n2/retry", () => apiError(409, "NOTIFICATION_EXPIRED", "This message is out of date and can no longer be sent"));
    renderWithAuth(<Harness />, { session: session("RECEPTIONIST") });
    const failed = (await screen.findAllByRole("listitem"))[1];
    await userEvent.click(within(failed).getByRole("button", { name: "Retry" }));
    expect(await screen.findByRole("alert")).toHaveTextContent("This message is out of date and can no longer be sent");
  });
});
