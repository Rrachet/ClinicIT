import { describe, expect, it } from "vitest";
import { session as fixture } from "@/test/fixtures";
import { canEnter, homeFor } from "./routing";
import { clearSession, loadSession, saveSession } from "./session";

describe("role routing", () => {
  it("sends each role to its own console", () => {
    expect(homeFor("RECEPTIONIST")).toBe("/reception");
    expect(homeFor("ADMIN")).toBe("/reception");
    expect(homeFor("DOCTOR")).toBe("/doctor");
  });

  it("keeps doctors out of reception and front desk out of the doctor console", () => {
    expect(canEnter("DOCTOR", "reception")).toBe(false);
    expect(canEnter("RECEPTIONIST", "doctor")).toBe(false);
    expect(canEnter("ADMIN", "reception")).toBe(true);
    expect(canEnter("DOCTOR", "doctor")).toBe(true);
  });

  it("opens clinic analytics to admins only", () => {
    expect(canEnter("ADMIN", "admin")).toBe(true);
    expect(canEnter("RECEPTIONIST", "admin")).toBe(false);
    expect(canEnter("DOCTOR", "admin")).toBe(false);
  });
});

describe("session storage", () => {
  it("round-trips a session and clears it", () => {
    saveSession(fixture("RECEPTIONIST"));
    expect(loadSession()?.user.role).toBe("RECEPTIONIST");
    clearSession();
    expect(loadSession()).toBeNull();
  });

  it("discards an expired session", () => {
    saveSession({ ...fixture("DOCTOR"), expiresAt: "2020-01-01T00:00:00Z" });
    expect(loadSession()).toBeNull();
    expect(sessionStorage.length).toBe(0);
  });

  it("ignores corrupted storage", () => {
    sessionStorage.setItem("clinicit.session", "{not json");
    expect(loadSession()).toBeNull();
  });
});
