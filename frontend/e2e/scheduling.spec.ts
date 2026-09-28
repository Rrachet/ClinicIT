import { expect, test } from "@playwright/test";
import { asAdmin, createDoctor, e2eState, signIn } from "./state";

/** A date some days ahead, "YYYY-MM-DD" (a week ahead is in the future in any clinic zone). */
function daysAhead(days: number): string {
  const d = new Date();
  d.setUTCDate(d.getUTCDate() + days);
  return d.toISOString().slice(0, 10);
}

test("an admin sets a doctor's week and leave, and reception can only book free slots", async ({ browser }) => {
  const state = e2eState();
  const doctor = await createDoctor(`Dr. Schedule ${state.run}`);
  const leaveDay = daysAhead(7);
  const workDay = daysAhead(8);

  // Admin: every day 09:00-17:00 with 30-minute appointments, and one day of leave.
  const admin = await (await browser.newContext()).newPage();
  await signIn(admin, process.env.E2E_ADMIN_EMAIL!, process.env.E2E_ADMIN_PASSWORD!);
  await admin.getByRole("link", { name: "Schedules" }).click();
  await expect(admin).toHaveURL(/\/admin\/schedules$/);
  await admin.getByRole("combobox", { name: "Doctor", exact: true }).selectOption({ label: doctor.displayName });
  await expect(admin.getByText("No leave planned.")).toBeVisible();
  for (const day of ["Monday", "Tuesday", "Wednesday", "Thursday", "Friday", "Saturday", "Sunday"]) {
    await admin.getByLabel(`${day}: works`).check();
  }
  await admin.getByRole("combobox", { name: "Appointment length" }).selectOption("30");
  await admin.getByRole("button", { name: "Save weekly hours" }).click();
  await expect(admin.getByText("Schedule saved.")).toBeVisible();

  await admin.getByLabel("First day").fill(leaveDay);
  await admin.getByLabel("Last day").fill(leaveDay);
  await admin.getByLabel("Note for staff (optional)").fill("Conference");
  await admin.getByRole("button", { name: "Add leave" }).click();
  await expect(admin.getByText("Leave added.")).toBeVisible();
  await expect(admin.getByRole("list", { name: "Planned leave" })).toContainText(`${leaveDay} · Conference`);

  // Reception: the leave day has nothing to offer; the next day starts at 09:00.
  const desk = await (await browser.newContext()).newPage();
  await signIn(desk, state.receptionistEmail, state.password);
  const book = async (patientName: string) => {
    await desk.getByLabel("Find patient").fill(patientName);
    await desk.getByRole("button", { name: "+ Register new patient" }).click();
    await desk.getByLabel("Phone").fill("90000 22222");
    await desk.getByRole("button", { name: "Register patient" }).click();
    await desk.locator(".picked").filter({ hasText: patientName }).waitFor();
    await desk.getByRole("combobox", { name: "Doctor", exact: true }).selectOption({ label: doctor.displayName });
    await desk.getByLabel(/Patient is here now/).uncheck();
  };

  await book(`Leave Day Patient ${state.run}`);
  await desk.getByLabel("Date").fill(leaveDay);
  await expect(desk.getByRole("status").filter({ hasText: "No free slots on this day." })).toBeVisible();
  await expect(desk.getByRole("button", { name: "Book appointment" })).toBeDisabled();

  await desk.getByLabel("Date").fill(workDay);
  const time = desk.getByRole("combobox", { name: "Time", exact: true });
  await expect(time).toHaveValue("09:00");
  await desk.getByRole("button", { name: "Book appointment" }).click();
  await expect(desk.getByLabel("Find patient")).toHaveValue("");

  // The 09:00 slot is now taken: the next patient is offered 09:30 first.
  await book(`Second Patient ${state.run}`);
  await desk.getByLabel("Date").fill(workDay);
  await expect(desk.getByRole("combobox", { name: "Time", exact: true })).toHaveValue("09:30");

  const booked = await asAdmin<{ scheduledAt: string; walkIn: boolean }[]>(
    "GET", `/api/v1/appointments?date=${workDay}&doctorId=${doctor.id}`,
  );
  expect(booked.map((a) => [a.scheduledAt, a.walkIn])).toEqual([[`${workDay}T09:00:00`, false]]);
});
