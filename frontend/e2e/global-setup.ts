import { writeFileSync } from "node:fs";
import { join } from "node:path";

/**
 * Seeds staff for this run through the real admin API: a doctor profile, a doctor login
 * and a receptionist. Unique per run so repeated runs against one database don't collide.
 */
export default async function globalSetup() {
  const api = process.env.E2E_API_URL ?? "http://localhost:8080";
  const adminEmail = required("E2E_ADMIN_EMAIL");
  const adminPassword = required("E2E_ADMIN_PASSWORD");
  const run = Date.now().toString(36);

  const login = await post(api, "/api/v1/auth/login", { email: adminEmail, password: adminPassword });
  const token = login.accessToken as string;

  const doctor = await post(api, "/api/v1/doctors", { displayName: `Dr. Mehra ${run}`, specialization: "General" }, token);
  const colleague = await post(api, "/api/v1/doctors", { displayName: `Dr. Rao ${run}`, specialization: "Paediatrics" }, token);
  const password = "e2e-staff-password-1";
  const doctorEmail = `doctor.${run}@e2e.clinicit.test`;
  const receptionistEmail = `desk.${run}@e2e.clinicit.test`;
  await post(api, "/api/v1/users", { email: doctorEmail, fullName: `Dr. Mehra ${run}`, password, role: "DOCTOR", doctorProfileId: doctor.id }, token);
  await post(api, "/api/v1/users", { email: receptionistEmail, fullName: `Desk ${run}`, password, role: "RECEPTIONIST" }, token);

  writeFileSync(
    join(__dirname, ".e2e-state.json"),
    JSON.stringify(
      {
        run,
        password,
        doctorEmail,
        receptionistEmail,
        doctorId: doctor.id,
        doctorName: doctor.displayName,
        colleagueId: colleague.id,
        colleagueName: colleague.displayName,
      },
      null,
      2,
    ),
  );
}

function required(name: string): string {
  const value = process.env[name];
  if (!value) throw new Error(`${name} must be set for end-to-end tests`);
  return value;
}

async function post(base: string, path: string, body: unknown, token?: string) {
  const response = await fetch(base + path, {
    method: "POST",
    headers: { "Content-Type": "application/json", ...(token ? { Authorization: `Bearer ${token}` } : {}) },
    body: JSON.stringify(body),
  });
  if (!response.ok) throw new Error(`${path} failed: ${response.status} ${await response.text()}`);
  return response.json();
}
