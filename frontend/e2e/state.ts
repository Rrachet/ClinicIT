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
}

export function e2eState(): E2EState {
  return JSON.parse(readFileSync(join(__dirname, ".e2e-state.json"), "utf8"));
}

export async function signIn(page: Page, email: string, password: string) {
  await page.goto("/login");
  await page.getByLabel("Email").fill(email);
  await page.getByLabel("Password").fill(password);
  await page.getByRole("button", { name: "Sign in" }).click();
}
