import { expect, test } from "@playwright/test";
import { bookFromReception, e2eState, signIn } from "./state";

/**
 * Not a check: captures the three screens with realistic data for visual review.
 * Opt-in: CAPTURE_SCREENSHOTS=1 scripts/e2e.sh visual-review
 */
test.skip(!process.env.CAPTURE_SCREENSHOTS, "set CAPTURE_SCREENSHOTS=1 to capture screenshots");

test("capture reception, doctor and patient screens", async ({ browser }) => {
  const state = e2eState();
  const out = "test-results/screens";
  const reception = await (await browser.newContext({ viewport: { width: 1440, height: 900 } })).newPage();
  await signIn(reception, state.receptionistEmail, state.password);
  await expect(reception.getByRole("status").filter({ hasText: "Live" })).toBeVisible();

  const names = ["Rahul Kumar", "Asha Rao", "Vikram Singh", "Meera Nair"];
  let link = "";
  for (const [i, name] of names.entries()) {
    await bookFromReception(reception, { patientName: `${name} ${state.run}`, doctorName: i === 3 ? state.colleagueName : state.doctorName, checkInNow: true });
    if (i === 2) {
      const patientName = `${name} ${state.run}`;
      await reception.getByRole("row").filter({ hasText: patientName }).getByRole("button", { name: `Messages: ${patientName}` }).click();
      const dialog = reception.getByRole("dialog");
      await expect(dialog.getByRole("listitem").filter({ hasText: "Queue link" })).toContainText("Sent");
      link = await dialog.getByLabel(/Status link/).inputValue();
      await reception.screenshot({ path: `${out}/messages.png` });
      await dialog.getByRole("button", { name: "Done" }).click();
    }
  }
  await bookFromReception(reception, { patientName: `Later Patient ${state.run}`, doctorName: state.doctorName, checkInNow: false, time: "18:30" });
  const card = reception.getByRole("article", { name: `Queue for ${state.doctorName}` });
  await card.getByRole("button", { name: /^Call #/ }).click();
  await expect(card.getByTestId(`current-token-${state.doctorId}`)).not.toHaveText("—");
  await reception.screenshot({ path: `${out}/reception.png`, fullPage: true });

  const doctor = await (await browser.newContext({ viewport: { width: 1440, height: 900 } })).newPage();
  await signIn(doctor, state.doctorEmail, state.password);
  await expect(doctor.getByTestId("doctor-current-token")).toBeVisible();
  await doctor.screenshot({ path: `${out}/doctor.png`, fullPage: true });

  const phone = await (await browser.newContext({ viewport: { width: 390, height: 844 }, isMobile: true })).newPage();
  await phone.goto(link);
  await expect(phone.getByTestId("patient-token")).toBeVisible();
  await phone.screenshot({ path: `${out}/patient.png` });

  await reception.getByRole("row").filter({ hasText: `Later Patient ${state.run}` }).getByRole("button", { name: /^Cancel/ }).click();
  await reception.screenshot({ path: `${out}/confirm.png` });
  await reception.getByRole("alertdialog").getByRole("button", { name: "Keep it" }).click();

  // Leave the doctor free for the tests that run after this one.
  await doctor.getByRole("button", { name: "Start consultation" }).click();
  await doctor.getByRole("button", { name: "Complete consultation" }).click();
  await expect(doctor.getByText("No patient with you.")).toBeVisible();
});
