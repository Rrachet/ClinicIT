import { expect, test, type Browser } from "@playwright/test";
import { bookFromReception, createDoctor, e2eState, signIn } from "./state";

async function receptionPage(browser: Browser) {
  const state = e2eState();
  const context = await browser.newContext();
  const page = await context.newPage();
  await signIn(page, state.receptionistEmail, state.password);
  await expect(page).toHaveURL(/\/reception$/);
  await expect(page.getByRole("status").filter({ hasText: "Live" })).toBeVisible();
  return { context, page, state };
}

test("walk-in: one click books, checks in and queues; the patient follows along on their phone", async ({ browser }) => {
  // A doctor of its own, so this patient is first in line whatever other tests left queued.
  const walkInDoctor = await createDoctor(`Dr. Walk-in ${e2eState().run}`);
  const { context, page: reception, state } = await receptionPage(browser);
  const patientName = `Walk In ${state.run}`;

  await bookFromReception(reception, { patientName, doctorName: walkInDoctor.displayName, checkInNow: true });

  // No dialog to copy from: the patient is messaged the link automatically.
  const notice = reception.getByRole("status").filter({ hasText: "The queue link is being sent to them." });
  await expect(notice).toContainText(`${patientName} is token #`);
  const token = (await notice.textContent())!.match(/token #(\d+)/)![1];

  // Reception can see what was sent. The development provider records instead of sending.
  await reception.getByRole("row").filter({ hasText: patientName }).getByRole("button", { name: `Messages: ${patientName}` }).click();
  const messages = reception.getByRole("dialog", { name: `Messages for token #${token}` });
  const queueLink = messages.getByRole("listitem").filter({ hasText: "Queue link" });
  await expect(queueLink).toContainText("Sent");
  await expect(queueLink).toContainText(/SMS to •••\d{4}/);
  const link = await messages.getByLabel(/Status link/).inputValue();
  await expect(queueLink).toContainText(`Your token is #${token}. Follow your place in the queue: ${link}`);
  await expect(queueLink).not.toContainText(patientName);
  await messages.getByRole("button", { name: "Done" }).click();

  // Anonymous patient on a phone: no login, no staff data.
  const phone = await browser.newContext({ viewport: { width: 390, height: 844 }, isMobile: true, hasTouch: true });
  const patient = await phone.newPage();
  const publicRequests: string[] = [];
  patient.on("request", (request) => publicRequests.push(request.url()));
  await patient.goto(link);
  await expect(patient.getByTestId("patient-token")).toHaveText(`#${token}`);
  await expect(patient.getByText(patientName)).toHaveCount(0);

  await expect(patient.getByRole("heading", { name: "You're next" })).toBeVisible();

  const card = reception.getByRole("article", { name: `Queue for ${walkInDoctor.displayName}` });
  await card.getByRole("button", { name: `Call #${token}` }).click();
  // The patient page polls; no refresh by the user.
  await expect(patient.getByRole("heading", { name: "It's your turn" })).toBeVisible({ timeout: 25_000 });

  // ...and they were messaged too.
  await reception.getByRole("row").filter({ hasText: patientName }).getByRole("button", { name: `Messages: ${patientName}` }).click();
  await expect(
    reception.getByRole("dialog").getByRole("listitem").filter({ hasText: "Your turn" }),
  ).toContainText(`token #${token}, it's your turn. Please go in now.`);

  expect(publicRequests.some((url) => url.includes("/api/v1/public/queue-status/"))).toBe(true);
  expect(publicRequests.some((url) => url.includes("/ws") || url.includes("/api/v1/queues"))).toBe(false);
  await phone.close();
  await context.close();
});

test("skip, back in queue, no-show and cancel, with confirmation for destructive steps", async ({ browser }) => {
  const { context, page: reception, state } = await receptionPage(browser);
  const skipped = `Skip Me ${state.run}`;
  const cancelled = `Cancel Me ${state.run}`;

  await bookFromReception(reception, { patientName: skipped, doctorName: state.colleagueName, checkInNow: true });
  const skippedRow = reception.getByRole("row").filter({ hasText: skipped });
  await expect(skippedRow.getByText("Waiting")).toBeVisible();

  await skippedRow.getByRole("button", { name: `Skip: ${skipped}` }).click();
  await expect(skippedRow.getByText("Skipped")).toBeVisible();
  await skippedRow.getByRole("button", { name: `Back in queue: ${skipped}` }).click();
  await expect(skippedRow.getByText("Waiting")).toBeVisible();
  await skippedRow.getByRole("button", { name: `Skip: ${skipped}` }).click();

  // No-show needs confirmation; "Keep it" changes nothing.
  await skippedRow.getByRole("button", { name: `No-show: ${skipped}` }).click();
  await reception.getByRole("alertdialog").getByRole("button", { name: "Keep it" }).click();
  await expect(skippedRow.getByText("Skipped")).toBeVisible();
  await skippedRow.getByRole("button", { name: `No-show: ${skipped}` }).click();
  await reception.getByRole("alertdialog").getByRole("button", { name: "Mark no-show" }).click();
  await expect(skippedRow.getByText("No-show")).toBeVisible();

  await bookFromReception(reception, { patientName: cancelled, doctorName: state.colleagueName, checkInNow: false });
  const cancelledRow = reception.getByRole("row").filter({ hasText: cancelled });
  await cancelledRow.getByRole("button", { name: `Cancel: ${cancelled}` }).click();
  await reception.getByRole("alertdialog").getByRole("button", { name: "Cancel appointment" }).click();
  await expect(cancelledRow.getByText("Cancelled")).toBeVisible();
  await expect(cancelledRow.getByRole("button")).toHaveCount(0);

  await context.close();
});

test("a confirmed patient who never arrives can be recorded as a no-show once their time has passed", async ({ browser }) => {
  const { context, page: reception, state } = await receptionPage(browser);
  const name = `Never Came ${state.run}`;

  await bookFromReception(reception, { patientName: name, doctorName: state.colleagueName, checkInNow: false, time: "00:05" });
  const row = reception.getByRole("row").filter({ hasText: name });
  await row.getByRole("button", { name: `Confirm: ${name}` }).click();
  await row.getByRole("button", { name: `No-show: ${name}` }).click();
  await reception.getByRole("alertdialog").getByRole("button", { name: "Mark no-show" }).click();
  await expect(row.getByText("No-show")).toBeVisible();
  await context.close();
});

test("a doctor sees only their own queue", async ({ browser }) => {
  const state = e2eState();
  const { context: receptionContext, page: reception } = await receptionPage(browser);
  const doctorContext = await browser.newContext();
  const doctor = await doctorContext.newPage();
  await signIn(doctor, state.doctorEmail, state.password);
  await expect(doctor.getByRole("status").filter({ hasText: "Live" })).toBeVisible();

  const colleaguesPatient = `Colleague Patient ${state.run}`;
  const ownPatient = `Own Patient ${state.run}`;
  await bookFromReception(reception, { patientName: colleaguesPatient, doctorName: state.colleagueName, checkInNow: true });
  await bookFromReception(reception, { patientName: ownPatient, doctorName: state.doctorName, checkInNow: true });

  await expect(doctor.locator(".queue-list")).toContainText(ownPatient);
  await expect(doctor.getByText(colleaguesPatient)).toHaveCount(0);

  await receptionContext.close();
  await doctorContext.close();
});

test("screens are role-gated and a signed-out token stops working", async ({ browser }) => {
  const state = e2eState();
  const anonymous = await (await browser.newContext()).newPage();
  await anonymous.goto("/reception");
  await expect(anonymous).toHaveURL(/\/login$/);

  const doctorContext = await browser.newContext();
  const doctor = await doctorContext.newPage();
  await signIn(doctor, state.doctorEmail, state.password);
  await expect(doctor).toHaveURL(/\/doctor$/);
  await doctor.goto("/reception");
  await expect(doctor).toHaveURL(/\/doctor$/);

  // Bad credentials.
  await anonymous.goto("/login");
  await anonymous.getByLabel("Email").fill(state.receptionistEmail);
  await anonymous.getByLabel("Password").fill("definitely wrong password");
  await anonymous.getByRole("button", { name: "Sign in" }).click();
  await expect(anonymous.getByRole("form", { name: "Sign in" }).getByRole("alert")).toHaveText("Email or password is incorrect.");

  // Signing out revokes the token on the server.
  const token = await doctor.evaluate(() => JSON.parse(sessionStorage.getItem("clinicit.session")!).token as string);
  await doctor.getByRole("button", { name: "Sign out" }).click();
  await expect(doctor).toHaveURL(/\/login$/);
  const api = process.env.E2E_API_URL ?? "http://localhost:8080";
  const response = await doctor.request.get(`${api}/api/v1/auth/me`, { headers: { Authorization: `Bearer ${token}` } });
  expect(response.status()).toBe(401);
  await doctorContext.close();
});
