// Captures the README screenshots from a running demo stack (docs/DEMO.md): the API with
// CLINICIT_DEMO_ENABLED on :8080 and this app on :3000. Everything shown is fictional demo data.
//
//   node scripts/capture-screens.mjs [outDir]
import { chromium } from "@playwright/test";

const web = process.env.WEB_URL ?? "http://localhost:3000";
const api = process.env.API_URL ?? "http://localhost:8080";
const password = process.env.DEMO_PASSWORD ?? "local-demo-only-2026";
const out = process.argv[2] ?? "../docs/screenshots";

const browser = await chromium.launch();

async function signedIn(email, viewport = { width: 1440, height: 900 }) {
  const context = await browser.newContext({ viewport, deviceScaleFactor: 1 });
  const page = await context.newPage();
  await page.goto(`${web}/login`);
  await page.getByLabel("Email").fill(email);
  await page.getByLabel("Password").fill(password);
  await page.getByRole("button", { name: "Sign in" }).click();
  await page.waitForURL((url) => !url.pathname.startsWith("/login"));
  return page;
}

const reception = await signedIn("reception@demo.clinicit.local");
await reception.getByRole("status").filter({ hasText: "Live" }).waitFor();
await reception.waitForTimeout(1500);
await reception.screenshot({ path: `${out}/reception.png` });

const doctor = await signedIn("ananya.reddy@demo.clinicit.local");
await doctor.getByRole("status").filter({ hasText: "Live" }).waitFor();
await doctor.waitForTimeout(1000);
await doctor.screenshot({ path: `${out}/doctor.png` });

const admin = await signedIn("admin@demo.clinicit.local");
await admin.goto(`${web}/admin`);
await admin.getByRole("table", { name: "Daily trends" }).waitFor();
await admin.waitForTimeout(1000);
await admin.screenshot({ path: `${out}/analytics.png`, fullPage: true });
await admin.goto(`${web}/admin/schedules`);
await admin.getByRole("heading", { name: "Weekly hours" }).waitFor();
await admin.screenshot({ path: `${out}/schedules.png` });

// A waiting patient's status page, as it looks on a phone.
const token = (await (await fetch(`${api}/api/v1/auth/login`, {
  method: "POST",
  headers: { "Content-Type": "application/json" },
  body: JSON.stringify({ email: "reception@demo.clinicit.local", password }),
})).json()).accessToken;
const auth = { headers: { Authorization: `Bearer ${token}` } };
const doctors = await (await fetch(`${api}/api/v1/doctors`, auth)).json();
const siddiqui = doctors.find((d) => d.displayName.includes("Siddiqui"));
const board = await (await fetch(`${api}/api/v1/queues/today?doctorId=${siddiqui.id}`, auth)).json();
const waiting = board.entries.filter((e) => e.status === "WAITING").at(-1);
const phone = await (await browser.newContext({ viewport: { width: 390, height: 780 }, deviceScaleFactor: 2 })).newPage();
await phone.goto(`${web}/status/${waiting.statusCode}`);
await phone.getByTestId("patient-token").waitFor();
await phone.screenshot({ path: `${out}/patient-status.png` });

await browser.close();
console.log(`Screenshots written to ${out}`);
