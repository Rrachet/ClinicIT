import { readFileSync } from "node:fs";
import { join } from "node:path";
import { expect, test } from "@playwright/test";
import { asAdmin, bookFromReception, createDoctor, e2eState, signIn } from "./state";

const ML = process.env.E2E_ML_URL ?? "http://localhost:18000";

test("the ML service answers the schema-1 request with its trained model and version", async ({ request }) => {
  const contract = JSON.parse(readFileSync(join(__dirname, "../../ml/contracts/predict_request_v1.json"), "utf8"));
  const response = await request.post(`${ML}/predict/wait-time`, { data: contract.request });
  expect(response.ok()).toBe(true);
  const body = await response.json();

  expect(body.modelVersion).toMatch(/^wait-/);
  expect(body.predictions[0]).toMatchObject({ source: "MODEL", reason: null });
  expect(body.predictions[0].lowerBoundMinutes).toBeLessThanOrEqual(body.predictions[0].estimatedWaitMinutes);
  // No history for the doctor in the second row: the service declines to guess.
  expect(body.predictions[1]).toMatchObject({ source: "BASELINE", reason: "INSUFFICIENT_HISTORY" });

  const invalid = await request.post(`${ML}/predict/wait-time`, { data: { schemaVersion: "2", instances: [] } });
  expect(invalid.status()).toBe(400);
});

test("reception and the patient see an approximate wait, never a promise", async ({ browser }) => {
  // A new doctor: no consultation history, so the real ML service hands back to the baseline.
  const doctor = await createDoctor(`Dr. Estimate ${e2eState().run}`);
  const state = e2eState();
  const desk = await (await browser.newContext()).newPage();
  await signIn(desk, state.receptionistEmail, state.password);
  await expect(desk.getByRole("status").filter({ hasText: "Live" })).toBeVisible();

  const first = `Estimate First ${state.run}`;
  const second = `Estimate Second ${state.run}`;
  await bookFromReception(desk, { patientName: first, doctorName: doctor.displayName, checkInNow: true });
  await bookFromReception(desk, { patientName: second, doctorName: doctor.displayName, checkInNow: true });

  // Reception: one patient ahead × the default 10-minute consultation.
  const card = desk.getByRole("article", { name: `Queue for ${doctor.displayName}` });
  const estimate = card.getByRole("listitem").filter({ hasText: second }).getByText(/Estimated wait: ~\d+ min/);
  await expect(estimate).toHaveText("Estimated wait: ~10 min");
  await expect(estimate).toHaveAttribute("title", /Rough estimate/);

  // Spring asked the real ML service, which declined for lack of history (not an outage).
  const estimates = await asAdmin<{ entries: { tokenNumber: number; source: string; fallbackReason: string }[] }>(
    "GET", `/api/v1/queues/today/wait-estimates?doctorId=${doctor.id}`,
  );
  expect(estimates.entries).toHaveLength(2);
  expect(estimates.entries[1]).toMatchObject({ source: "BASELINE", fallbackReason: "INSUFFICIENT_HISTORY" });

  // The patient's page shows a range.
  const board = await asAdmin<{ entries: { statusCode: string; patientName: string }[] }>(
    "GET", `/api/v1/queues/today?doctorId=${doctor.id}`,
  );
  const code = board.entries.find((e) => e.patientName === second)!.statusCode;
  const phone = await (await browser.newContext({ viewport: { width: 390, height: 844 }, isMobile: true })).newPage();
  await phone.goto(`/status/${code}`);
  await expect(phone.getByTestId("patient-estimate")).toHaveText("5–20 min");
  await expect(phone.getByText(/not an appointment time/)).toBeVisible();

  // When the first patient is called, the second is next and the estimates move.
  await card.getByRole("button", { name: /^Call #\d+$/ }).click();
  await expect(card.getByRole("listitem").filter({ hasText: second })).toContainText("Estimated wait: ~1 min");
  await phone.reload();
  await expect(phone.getByTestId("patient-estimate")).toHaveText("under 5 min");
});
