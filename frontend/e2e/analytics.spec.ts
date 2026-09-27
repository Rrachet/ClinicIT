import { expect, test } from "@playwright/test";
import { asAdmin, bookFromReception, createDoctor, e2eState, signIn } from "./state";

test("admin sees a completed visit in the clinic analytics, computed from its recorded history", async ({ browser }) => {
  // A doctor of its own, so the figures filtered to this doctor are exactly this test's.
  const doctor = await createDoctor(`Dr. Analytics ${e2eState().run}`);
  const state = e2eState();

  // Reception checks a walk-in in and calls them, through the UI.
  const desk = await (await browser.newContext()).newPage();
  await signIn(desk, state.receptionistEmail, state.password);
  await expect(desk).toHaveURL(/\/reception$/);
  // Analytics is not a receptionist's screen.
  await expect(desk.getByRole("link", { name: "Analytics" })).toHaveCount(0);
  await bookFromReception(desk, { patientName: `Analytics Patient ${state.run}`, doctorName: doctor.displayName, checkInNow: true });
  const card = desk.getByRole("article", { name: `Queue for ${doctor.displayName}` });
  await card.getByRole("button", { name: /^Call #\d+$/ }).click();
  await expect(card.getByRole("button", { name: /^Call #/ })).toHaveCount(0);

  // The doctor sees the patient and completes the consultation (their console is covered elsewhere).
  const called = await asAdmin<{ entries: { id: string; status: string }[] }>(
    "GET", `/api/v1/queues/today?doctorId=${doctor.id}`,
  ).then((board) => board.entries.find((e) => e.status === "CALLED")!);
  await asAdmin("POST", `/api/v1/queue-entries/${called.id}/start`);
  await asAdmin("POST", `/api/v1/queue-entries/${called.id}/complete`);

  // Receptionists are sent back to their console if they open the analytics URL.
  await desk.goto("/admin");
  await expect(desk).toHaveURL(/\/reception$/);

  // The admin lands on reception and follows the Analytics link.
  const admin = await (await browser.newContext()).newPage();
  await signIn(admin, process.env.E2E_ADMIN_EMAIL!, process.env.E2E_ADMIN_PASSWORD!);
  await expect(admin).toHaveURL(/\/reception$/);
  await admin.getByRole("link", { name: "Analytics" }).click();
  await expect(admin).toHaveURL(/\/admin$/);

  await admin.getByRole("combobox", { name: "Doctor" }).selectOption({ label: doctor.displayName });
  const today = admin.getByRole("region", { name: `Today · ${doctor.displayName}` });
  await expect(today.locator(".kpi").filter({ hasText: "Patients" })).toContainText("1checked in · 1 booked");
  await expect(today.locator(".kpi").filter({ hasText: "Completed" })).toContainText("1");
  await expect(today.locator(".kpi").filter({ hasText: "Median wait" })).not.toContainText("—");
  await expect(today.locator(".kpi").filter({ hasText: "Avg consultation" })).not.toContainText("—");
  await expect(today.locator(".kpi").filter({ hasText: "No-show rate" })).toContainText("0%");
  await expect(admin.getByTestId("current-queue-length")).toHaveText("0");

  const doctorRow = admin.getByRole("table", { name: "Doctors" }).getByRole("row", { name: new RegExp(doctor.displayName) });
  await expect(doctorRow.getByRole("cell").first()).toHaveText("1");

  const hours = admin.getByRole("table", { name: "Queue by hour" }).getByRole("row");
  await expect(hours.nth(1)).toBeVisible();
});
