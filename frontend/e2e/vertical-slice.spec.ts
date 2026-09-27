import { expect, test } from "@playwright/test";
import { e2eState, signIn } from "./state";

/**
 * The Phase 5 vertical slice, against the real backend:
 * login → register patient → book → confirm → arrive → join queue → call next →
 * doctor sees it live → start → complete → reception sees it live.
 */
test("receptionist and doctor run one patient through the clinic, live", async ({ browser }) => {
  const state = e2eState();
  const patientName = `Rahul Slice ${state.run}`;

  const receptionContext = await browser.newContext();
  const doctorContext = await browser.newContext();
  const reception = await receptionContext.newPage();
  const doctor = await doctorContext.newPage();

  // Both sign in and land on their own screens.
  await signIn(reception, state.receptionistEmail, state.password);
  await expect(reception).toHaveURL(/\/reception$/);
  await expect(reception.getByText("Today's Clinic")).toBeVisible();
  await expect(reception.getByRole("status").filter({ hasText: "Live" })).toBeVisible();

  await signIn(doctor, state.doctorEmail, state.password);
  await expect(doctor).toHaveURL(/\/doctor$/);
  await expect(doctor.getByText("No patient with you.")).toBeVisible();
  await expect(doctor.getByRole("status").filter({ hasText: "Live" })).toBeVisible();
  // From here on the doctor page must never reload: every change arrives over the WebSocket.
  let doctorNavigations = 0;
  doctor.on("framenavigated", (frame) => frame === doctor.mainFrame() && doctorNavigations++);

  // Register the patient.
  await reception.getByLabel("Find patient").fill(patientName);
  await reception.getByRole("button", { name: "+ Register new patient" }).click();
  await expect(reception.getByLabel("Full name")).toHaveValue(patientName);
  await reception.getByLabel("Phone").fill("98765 43210");
  await reception.getByRole("button", { name: "Register patient" }).click();
  await expect(reception.locator(".picked")).toContainText(patientName);

  // Book (without the walk-in shortcut, so each step is explicit).
  await reception.getByRole("combobox", { name: "Doctor", exact: true }).selectOption({ label: state.doctorName });
  await reception.getByLabel("Reason (optional)").fill("Follow-up");
  await reception.getByLabel(/Patient is here now/).uncheck();
  await reception.getByRole("button", { name: "Book appointment" }).click();

  const row = reception.getByRole("row").filter({ hasText: patientName });
  await expect(row.getByText("Booked")).toBeVisible();
  await row.getByRole("button", { name: `Confirm: ${patientName}` }).click();
  await expect(row.getByText("Confirmed")).toBeVisible();
  await row.getByRole("button", { name: `Mark arrived: ${patientName}` }).click();
  await expect(row.getByText("Arrived")).toBeVisible();
  await row.getByRole("button", { name: `Add to queue: ${patientName}` }).click();

  // Joining shows the patient's status link.
  const linkDialog = reception.getByRole("dialog", { name: /Queue link for token/ });
  await expect(linkDialog).toBeVisible();
  const tokenText = (await linkDialog.getByRole("heading").textContent())!.match(/#(\d+)/)![1];
  await linkDialog.getByRole("button", { name: "Done" }).click();
  await expect(row.getByText("Waiting")).toBeVisible();
  await expect(row.getByText(`#${tokenText}`)).toBeVisible();

  // The doctor sees the new patient in "Up next" without refreshing.
  await expect(doctor.locator(".queue-list")).toContainText(patientName);

  // Reception calls the patient.
  const card = reception.getByRole("article", { name: `Queue for ${state.doctorName}` });
  await card.getByRole("button", { name: `Call #${tokenText}` }).click();
  await expect(card.getByTestId(`current-token-${state.doctorId}`)).toHaveText(`#${tokenText}`);

  // Doctor's screen updates live.
  await expect(doctor.getByTestId("doctor-current-token")).toHaveText(`#${tokenText}`);
  await expect(doctor.locator(".current-name")).toHaveText(patientName);
  await expect(doctor.getByText("Follow-up")).toBeVisible();

  await doctor.getByRole("button", { name: "Start consultation" }).click();
  await expect(doctor.locator(".current-panel").getByText("In consultation")).toBeVisible();
  // ...and reception sees the consultation start live.
  await expect(row.getByText("In consultation")).toBeVisible();

  await doctor.getByRole("button", { name: "Complete consultation" }).click();
  await expect(doctor.getByText("No patient with you.")).toBeVisible();

  // Reception sees the result live.
  await expect(row.getByText("Completed")).toBeVisible();
  await expect(card.getByTestId(`current-token-${state.doctorId}`)).toHaveText("—");

  expect(doctorNavigations).toBe(0);
  await receptionContext.close();
  await doctorContext.close();
});
