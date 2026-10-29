import { screen } from "@testing-library/react";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import type { PublicQueueStatus } from "@/api/types";
import { apiError, fakeApi } from "@/test/fakeApi";
import { navigationMock } from "@/test/navigation";
import { renderWithAuth } from "@/test/render";
import { PatientStatus, pollDelay } from "./PatientStatus";
import { statusMessage } from "./statusMessage";

vi.mock("next/navigation", () => navigationMock);

const base: PublicQueueStatus = {
  clinicName: "City Clinic",
  doctorName: "Dr. Sharma",
  queueDate: "2026-03-10",
  tokenNumber: 27,
  status: "WAITING",
  currentToken: 23,
  patientsAhead: 3,
  estimatedWait: { estimatedWaitMinutes: 23, lowerBoundMinutes: 17, upperBoundMinutes: 31 },
};

describe("statusMessage", () => {
  it("counts patients ahead, and says 'You're next' at zero", () => {
    expect(statusMessage(base).headline).toBe("3 patients ahead of you");
    expect(statusMessage({ ...base, patientsAhead: 1 }).headline).toBe("1 patient ahead of you");
    expect(statusMessage({ ...base, patientsAhead: 0 })).toMatchObject({ tone: "next", headline: "You're next" });
  });

  it("tells a called patient where to go, and a skipped one how to rejoin", () => {
    expect(statusMessage({ ...base, status: "CALLED" })).toMatchObject({ tone: "go", detail: "Please go to Dr. Sharma now." });
    expect(statusMessage({ ...base, status: "CALLED", room: "Room 3" }).detail).toBe("Please go to Room 3 now (Dr. Sharma).");
    expect(statusMessage({ ...base, status: "SKIPPED" }).detail).toContain("tell the doctor or clinic staff");
  });
});

describe("PatientStatus page", () => {
  let api: ReturnType<typeof fakeApi>;
  beforeEach(() => {
    api = fakeApi();
    vi.stubGlobal("fetch", api.fetchMock);
  });
  afterEach(() => vi.unstubAllGlobals());

  it("confirms a fresh self check-in, and shows the room in large type once called", async () => {
    let current: PublicQueueStatus = { ...base, room: "Room 3" };
    api.route("GET /api/v1/public/queue-status/abcDEF123456789xyz_-", () => ({ body: current }));
    renderWithAuth(<PatientStatus code="abcDEF123456789xyz_-" justCheckedIn pollMs={20} nearPollMs={20} />);
    expect(await screen.findByText(/You're checked in/)).toBeInTheDocument();
    expect(screen.queryByTestId("patient-room")).not.toBeInTheDocument();
    expect(screen.getByRole("button", { name: "Alert me with sound and vibration" })).toBeInTheDocument();

    current = { ...current, status: "CALLED", patientsAhead: 0, currentToken: 27 };
    expect(await screen.findByRole("heading", { name: "It's your turn" })).toBeInTheDocument();
    expect(screen.getByTestId("patient-room")).toHaveTextContent("Room 3");
    expect(screen.queryByText(/You're checked in/)).not.toBeInTheDocument();
    expect(document.title).toBe("Your turn · ClinicIT");
  });

  it("shows the token, now serving and people ahead, without any login", async () => {
    api.route("GET /api/v1/public/queue-status/abcDEF123456789xyz_-", () => ({ body: base }));
    renderWithAuth(<PatientStatus code="abcDEF123456789xyz_-" />);

    expect(await screen.findByTestId("patient-token")).toHaveTextContent("#27");
    expect(screen.getByTestId("patient-current")).toHaveTextContent("#23");
    expect(screen.getByRole("heading", { name: "3 patients ahead of you" })).toBeInTheDocument();
    expect(api.calls[0].authorization).toBeUndefined();
  });

  it("shows the estimated wait as a range, and says it is an estimate", async () => {
    api.route("GET /api/v1/public/queue-status/code-estimate-1234", () => ({ body: base }));
    renderWithAuth(<PatientStatus code="code-estimate-1234" />);

    expect(await screen.findByTestId("patient-estimate")).toHaveTextContent("17–31 min");
    expect(screen.getByText("Estimated wait")).toBeInTheDocument();
    expect(screen.getByText(/not an appointment time/)).toBeInTheDocument();
  });

  it("shows no estimate when there is none or the patient has been called", async () => {
    api.route("GET /api/v1/public/queue-status/code-no-estimate-1", () => ({ body: { ...base, estimatedWait: null } }));
    renderWithAuth(<PatientStatus code="code-no-estimate-1" />);
    await screen.findByTestId("patient-token");
    expect(screen.queryByText("Estimated wait")).not.toBeInTheDocument();
  });

  it("drops the estimate once the patient is called", async () => {
    api.route("GET /api/v1/public/queue-status/code-called-12345", () => ({ body: { ...base, status: "CALLED" } }));
    renderWithAuth(<PatientStatus code="code-called-12345" />);
    await screen.findByTestId("patient-token");
    expect(screen.queryByTestId("patient-estimate")).not.toBeInTheDocument();
  });

  it("makes 'You're next' unmistakable", async () => {
    api.route("GET /api/v1/public/queue-status/code-1234567890123", () => ({ body: { ...base, patientsAhead: 0 } }));
    renderWithAuth(<PatientStatus code="code-1234567890123" />);
    expect(await screen.findByRole("heading", { name: "You're next" })).toBeInTheDocument();
  });

  it("explains an expired or wrong link", async () => {
    api.route("GET /api/v1/public/queue-status/old-code-12345678", () => apiError(404, "NOT_FOUND", "Queue status not found"));
    renderWithAuth(<PatientStatus code="old-code-12345678" />);
    expect(await screen.findByRole("heading", { name: "Link not valid" })).toBeInTheDocument();
  });

  it("updates by itself when the patient is called, checking more often near the front", async () => {
    let calls = 0;
    api.route("GET /api/v1/public/queue-status/code-live-update-1", () => {
      calls++;
      return { body: calls < 3 ? { ...base, patientsAhead: 0 } : { ...base, status: "CALLED", patientsAhead: 0 } };
    });
    // Normal polling is far too slow for this test: only the "near the front" pace can reach the call.
    renderWithAuth(<PatientStatus code="code-live-update-1" pollMs={60_000} nearPollMs={40} />);

    expect(await screen.findByRole("heading", { name: "You're next" })).toBeInTheDocument();
    expect(await screen.findByRole("heading", { name: "It's your turn" }, { timeout: 2000 })).toBeInTheDocument();
  });
});

describe("pollDelay", () => {
  it("checks often only when the patient is about to be or has just been called", () => {
    expect(pollDelay(null, 15_000, 5_000)).toBe(15_000);
    expect(pollDelay({ ...base, patientsAhead: 3 }, 15_000, 5_000)).toBe(15_000);
    expect(pollDelay({ ...base, patientsAhead: 1 }, 15_000, 5_000)).toBe(5_000);
    expect(pollDelay({ ...base, patientsAhead: 0 }, 15_000, 5_000)).toBe(5_000);
    expect(pollDelay({ ...base, status: "CALLED" }, 15_000, 5_000)).toBe(5_000);
    expect(pollDelay({ ...base, status: "COMPLETED" }, 15_000, 5_000)).toBe(15_000);
  });
});
