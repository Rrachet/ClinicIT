import { screen } from "@testing-library/react";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import type { PublicQueueStatus } from "@/api/types";
import { apiError, fakeApi } from "@/test/fakeApi";
import { navigationMock } from "@/test/navigation";
import { renderWithAuth } from "@/test/render";
import { PatientStatus } from "./PatientStatus";
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
};

describe("statusMessage", () => {
  it("counts patients ahead, and says 'You're next' at zero", () => {
    expect(statusMessage(base).headline).toBe("3 patients ahead of you");
    expect(statusMessage({ ...base, patientsAhead: 1 }).headline).toBe("1 patient ahead of you");
    expect(statusMessage({ ...base, patientsAhead: 0 })).toMatchObject({ tone: "next", headline: "You're next" });
  });

  it("tells a called patient to go in, and a skipped one to see reception", () => {
    expect(statusMessage({ ...base, status: "CALLED" })).toMatchObject({ tone: "go", detail: "Please go to Dr. Sharma now." });
    expect(statusMessage({ ...base, status: "SKIPPED" }).detail).toContain("reception");
  });
});

describe("PatientStatus page", () => {
  let api: ReturnType<typeof fakeApi>;
  beforeEach(() => {
    api = fakeApi();
    vi.stubGlobal("fetch", api.fetchMock);
  });
  afterEach(() => vi.unstubAllGlobals());

  it("shows the token, now serving and people ahead, without any login", async () => {
    api.route("GET /api/v1/public/queue-status/abcDEF123456789xyz_-", () => ({ body: base }));
    renderWithAuth(<PatientStatus code="abcDEF123456789xyz_-" />);

    expect(await screen.findByTestId("patient-token")).toHaveTextContent("#27");
    expect(screen.getByTestId("patient-current")).toHaveTextContent("#23");
    expect(screen.getByRole("heading", { name: "3 patients ahead of you" })).toBeInTheDocument();
    expect(api.calls[0].authorization).toBeUndefined();
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
});
