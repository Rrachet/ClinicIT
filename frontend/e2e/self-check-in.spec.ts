import { expect, test } from "@playwright/test";
import { asAdmin, e2eState, signIn } from "./state";

/**
 * No receptionist: a doctor goes live, a patient scans the QR code, checks in on their phone,
 * is matched to that doctor and is told which room to go to when called.
 */
test("self check-in: doctor goes live, patient checks in by QR and is sent to the room", async ({ browser }) => {
  const { run } = e2eState();
  const password = "self-check-in-doctor-1";
  const doctor = await asAdmin<{ id: string; displayName: string }>("POST", "/api/v1/doctors", {
    displayName: `Dr. Live ${run}`,
    specialization: "Paediatrics",
  });
  const email = `live.${run}@e2e.clinicit.test`;
  await asAdmin("POST", "/api/v1/users", { email, fullName: doctor.displayName, password, role: "DOCTOR", doctorProfileId: doctor.id });
  const visitName = `Child visit ${run}`;
  await asAdmin("POST", "/api/v1/visit-types", { name: visitName, anyDoctor: false, doctorIds: [doctor.id] });
  const clinic = await asAdmin<{ checkInCode: string }>("GET", "/api/v1/clinic");

  // The patient arrives before the doctor is live: that visit cannot be chosen yet.
  const phone = await browser.newContext({ viewport: { width: 390, height: 844 }, isMobile: true, hasTouch: true });
  const patient = await phone.newPage();
  await patient.goto(`/check-in/${clinic.checkInCode}`);
  await expect(patient.getByRole("heading", { name: "Check in" })).toBeVisible();
  const closedOption = patient.getByRole("radio", { name: new RegExp(visitName) });
  if (await closedOption.count()) await expect(closedOption).toBeDisabled();

  // The doctor signs in, sets the room and goes live.
  const doctorContext = await browser.newContext();
  const desk = await doctorContext.newPage();
  await signIn(desk, email, password);
  await expect(desk).toHaveURL(/\/doctor$/);
  await expect(desk.getByText("You're offline")).toBeVisible();
  await desk.getByLabel("Room").fill(`Room ${run}`);
  await desk.getByRole("button", { name: "Go live" }).click();
  await expect(desk.getByText("You're live")).toBeVisible();

  // The check-in page refreshes its options by itself; reload to skip the wait.
  await patient.reload();
  await patient.getByLabel(/Your name/).fill(`Self ${run}`);
  await patient.getByLabel("Phone number").fill("98765 43210");
  await patient.getByRole("radio", { name: new RegExp(visitName) }).check();
  await patient.getByLabel(/Anything the doctor should know/).fill("Cough for three days");
  await patient.getByRole("button", { name: "Check in" }).click();

  await expect(patient).toHaveURL(/\/status\/[A-Za-z0-9_-]+\?checkedIn=1$/);
  await expect(patient.getByText(/You're checked in/)).toBeVisible();
  await expect(patient.getByText(doctor.displayName)).toBeVisible();
  await expect(patient.getByRole("heading", { name: "You're next" })).toBeVisible();
  const token = (await patient.getByTestId("patient-token").textContent())!.replace("#", "");

  // Scanning again returns the same place.
  await patient.goto(`/check-in/${clinic.checkInCode}`);
  await patient.getByLabel(/Your name/).fill(`Self ${run}`);
  await patient.getByLabel("Phone number").fill("9876543210");
  await patient.getByRole("radio", { name: new RegExp(visitName) }).check();
  await patient.getByRole("button", { name: "Check in" }).click();
  await expect(patient.getByTestId("patient-token")).toHaveText(`#${token}`);

  // The doctor sees them live, with the visit type and note, and calls them.
  await expect(desk.getByRole("button", { name: `Call next · #${token}` })).toBeVisible();
  await desk.getByRole("button", { name: `Call next · #${token}` }).click();
  await expect(desk.getByText(`${visitName}: Cough for three days`)).toBeVisible();

  await expect(patient.getByRole("heading", { name: "It's your turn" })).toBeVisible({ timeout: 25_000 });
  await expect(patient.getByTestId("patient-room")).toHaveText(`Room ${run}`);

  // Offline again: no new patients for this doctor.
  await desk.getByRole("button", { name: "Go offline" }).click();
  await expect(desk.getByText("You're offline")).toBeVisible();
  await phone.close();
  await doctorContext.close();
});
