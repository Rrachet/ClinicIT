import { readFileSync } from "node:fs";
import { join } from "node:path";
import type { Page } from "@playwright/test";

export interface E2EState {
  run: string;
  password: string;
  doctorEmail: string;
  receptionistEmail: string;
  doctorId: string;
  doctorName: string;
  colleagueId: string;
  colleagueName: string;
}

export function e2eState(): E2EState {
  return JSON.parse(readFileSync(join(__dirname, ".e2e-state.json"), "utf8"));
}

/** Calls the real API as the bootstrap admin (for setup steps that have no UI for this role). */
export async function asAdmin<T>(method: "GET" | "POST", path: string, body?: unknown): Promise<T> {
  const api = process.env.E2E_API_URL ?? "http://localhost:8080";
  const login = await fetch(`${api}/api/v1/auth/login`, {
    method: "POST",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify({ email: process.env.E2E_ADMIN_EMAIL, password: process.env.E2E_ADMIN_PASSWORD }),
  }).then((r) => r.json());
  const response = await fetch(`${api}${path}`, {
    method,
    headers: { "Content-Type": "application/json", Authorization: `Bearer ${login.accessToken}` },
    body: body === undefined ? undefined : JSON.stringify(body),
  });
  if (!response.ok) throw new Error(`${method} ${path} failed: ${response.status}`);
  return response.json();
}

/** Creates a doctor through the real admin API, for tests that need a queue of their own. */
export function createDoctor(displayName: string): Promise<{ id: string; displayName: string }> {
  return asAdmin("POST", "/api/v1/doctors", { displayName });
}

/** Registers a patient and books them from the reception console's "New appointment" panel. */
export async function bookFromReception(
  page: Page,
  options: { patientName: string; doctorName: string; checkInNow: boolean; time?: string },
) {
  await page.getByLabel("Find patient").fill(options.patientName);
  await page.getByRole("button", { name: "+ Register new patient" }).click();
  await page.getByLabel("Phone").fill("90000 11111");
  await page.getByRole("button", { name: "Register patient" }).click();
  await page.locator(".picked").filter({ hasText: options.patientName }).waitFor();
  await page.getByRole("combobox", { name: "Doctor", exact: true }).selectOption({ label: options.doctorName });
  const checkbox = page.getByLabel(/Patient is here now/);
  if (options.checkInNow) await checkbox.check();
  else await checkbox.uncheck();
  // Only a later booking has a time; a walk-in is booked for "now" by the server.
  if (options.time) await page.getByLabel("Time", { exact: true }).fill(options.time);
  await page.getByRole("button", { name: options.checkInNow ? "Book and add to queue" : "Book appointment" }).click();
}

export async function signIn(page: Page, email: string, password: string) {
  await page.goto("/login");
  await page.getByLabel("Email").fill(email);
  await page.getByLabel("Password").fill(password);
  await page.getByRole("button", { name: "Sign in" }).click();
}
