import { expect, test } from "@playwright/test";
import { e2eState, signIn } from "./state";

test("an admin creates a receptionist, who can work at once; disabling them ends their session", async ({ browser }) => {
  const state = e2eState();
  const email = `new.desk.${state.run}@e2e.clinicit.test`;
  const password = "new-desk-password-1";

  const admin = await (await browser.newContext()).newPage();
  await signIn(admin, process.env.E2E_ADMIN_EMAIL!, process.env.E2E_ADMIN_PASSWORD!);
  await admin.getByRole("link", { name: "Team" }).click();
  await expect(admin).toHaveURL(/\/admin\/team$/);

  const form = admin.getByRole("form", { name: "Add a staff account" });
  await form.getByLabel("Full name").fill(`New Desk ${state.run}`);
  await form.getByLabel("Email").fill(email);
  await form.getByLabel("Role").selectOption("RECEPTIONIST");
  await form.getByLabel(/Initial password/).fill(password);
  await form.getByRole("button", { name: "Create account" }).click();
  await expect(admin.getByText(`New Desk ${state.run} can now sign in as receptionist.`, { exact: false })).toBeVisible();

  // The new receptionist signs in and lands on the reception console.
  const deskContext = await browser.newContext();
  const desk = await deskContext.newPage();
  await signIn(desk, email, password);
  await expect(desk).toHaveURL(/\/reception$/);
  await expect(desk.getByRole("region", { name: "Today at a glance" })).toBeVisible();
  const token = await desk.evaluate(() => JSON.parse(sessionStorage.getItem("clinicit.session")!).token as string);

  // Disabling revokes every session of theirs on the server.
  await admin.getByRole("button", { name: `Disable New Desk ${state.run}` }).click();
  await admin.getByRole("alertdialog").getByRole("button", { name: "Disable account" }).click();
  await expect(admin.getByText(`New Desk ${state.run} can no longer sign in`, { exact: false })).toBeVisible();

  const api = process.env.E2E_API_URL ?? "http://localhost:8080";
  expect((await desk.request.get(`${api}/api/v1/auth/me`, { headers: { Authorization: `Bearer ${token}` } })).status()).toBe(401);
  await desk.reload();
  // Sent to sign in, with a note that the session ended.
  await expect(desk).toHaveURL(/\/login\?reason=expired$/);
  await deskContext.close();
});
