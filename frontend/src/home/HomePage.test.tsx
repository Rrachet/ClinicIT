import { screen } from "@testing-library/react";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { fakeApi } from "@/test/fakeApi";
import { navigationMock } from "@/test/navigation";
import { session } from "@/test/fixtures";
import { renderWithAuth } from "@/test/render";
import { HomePage } from "./HomePage";

vi.mock("next/navigation", () => navigationMock);

describe("HomePage", () => {
  beforeEach(() => vi.stubGlobal("fetch", fakeApi().fetchMock));
  afterEach(() => vi.unstubAllGlobals());

  it("explains the product and offers staff sign-in when signed out", () => {
    renderWithAuth(<HomePage />);
    expect(screen.getByRole("heading", { level: 1 })).toHaveTextContent("Run the clinic day without a reception desk");
    expect(screen.getByRole("heading", { name: "How a patient moves through the clinic" })).toBeInTheDocument();
    expect(screen.getByRole("heading", { name: "Under the hood" })).toBeInTheDocument();
    const signIns = screen.getAllByRole("link", { name: "Staff sign in" });
    expect(signIns.length).toBeGreaterThan(0);
    signIns.forEach((link) => expect(link).toHaveAttribute("href", "/login"));
    expect(screen.queryByRole("link", { name: "Open your dashboard" })).not.toBeInTheDocument();
  });

  it.each([
    ["DOCTOR", "/doctor"],
    ["RECEPTIONIST", "/reception"],
    ["ADMIN", "/reception"],
  ] as const)("sends a signed-in %s to their dashboard", (role, href) => {
    renderWithAuth(<HomePage />, { session: session(role) });
    screen.getAllByRole("link", { name: "Open your dashboard" }).forEach((link) => expect(link).toHaveAttribute("href", href));
    expect(screen.queryByRole("link", { name: "Staff sign in" })).not.toBeInTheDocument();
  });

  it("never presents estimates as clinical advice", () => {
    renderWithAuth(<HomePage />);
    expect(screen.getByRole("heading", { name: "Operational, not clinical" })).toBeInTheDocument();
    expect(screen.getByText(/never diagnoses, prescribes/)).toBeInTheDocument();
  });
});
