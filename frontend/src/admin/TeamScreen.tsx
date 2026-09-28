"use client";

import Link from "next/link";
import { useCallback, useId, useState } from "react";

import type { ClinicApi } from "@/api/clinicApi";
import type { Clinic, Doctor, Role, User } from "@/api/types";
import { useAuth, useSession } from "@/auth/AuthProvider";
import { AppHeader } from "@/ui/AppHeader";
import { Button } from "@/ui/Button";
import { ConfirmDialog } from "@/ui/ConfirmDialog";
import { ErrorBanner, Notice } from "@/ui/Feedback";
import { FullPageSpinner } from "@/ui/Spinner";
import { useAsync } from "@/ui/useAsync";

const ROLE_LABEL: Record<Role, string> = { ADMIN: "Admin", RECEPTIONIST: "Receptionist", DOCTOR: "Doctor" };

/**
 * The clinic's people and name: doctors (and their schedules), staff accounts with their
 * roles, and the clinic's display name. Every change is an admin API call the server checks.
 */
export function TeamScreen() {
  const { api } = useAuth();
  const data = useAsync(useCallback(() => Promise.all([api.clinic(), api.doctors(), api.users()]), [api]));

  if (!data.data) {
    return data.error ? (
      <main className="page">
        <ErrorBanner error={data.error} onRetry={data.reload} />
      </main>
    ) : (
      <FullPageSpinner label="Loading the team" />
    );
  }
  const [clinic, doctors, users] = data.data;

  return (
    <>
      <AppHeader title="Team and clinic" clinicName={clinic.name} />
      <main className="page stack">
        <ClinicPanel api={api} clinic={clinic} onSaved={data.reload} />
        <DoctorsPanel api={api} doctors={doctors} users={users} onChanged={data.reload} />
        <StaffPanel api={api} doctors={doctors} users={users} onChanged={data.reload} />
      </main>
    </>
  );
}

function ClinicPanel({ api, clinic, onSaved }: { api: ClinicApi; clinic: Clinic; onSaved: () => void }) {
  const ids = useId();
  const [name, setName] = useState(clinic.name);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<unknown>(null);
  const [done, setDone] = useState(false);

  async function save(e: React.FormEvent) {
    e.preventDefault();
    setBusy(true);
    setError(null);
    setDone(false);
    try {
      await api.renameClinic(name);
      setDone(true);
      onSaved();
    } catch (err) {
      setError(err);
    } finally {
      setBusy(false);
    }
  }

  return (
    <form className="panel" aria-labelledby={`${ids}-title`} onSubmit={save}>
      <h2 id={`${ids}-title`}>Clinic</h2>
      <ErrorBanner error={error} onDismiss={() => setError(null)} />
      {done ? <Notice>Clinic name saved.</Notice> : null}
      <div className="row" style={{ alignItems: "flex-end" }}>
        <label className="field" style={{ flex: "1 1 20rem" }}>
          <span>Name (shown to staff and on patients&apos; status pages)</span>
          <input value={name} onChange={(e) => setName(e.target.value)} required maxLength={150} />
        </label>
        <Button type="submit" variant="primary" busy={busy} disabled={!name.trim() || name.trim() === clinic.name}>
          Save name
        </Button>
      </div>
      <p className="hint">
        Time zone: <strong>{clinic.timezone}</strong>. It decides which day each queue belongs to, so it is set when the
        clinic is created and not changed while it operates.
      </p>
    </form>
  );
}

function DoctorsPanel({
  api,
  doctors,
  users,
  onChanged,
}: {
  api: ClinicApi;
  doctors: Doctor[];
  users: User[];
  onChanged: () => void;
}) {
  const ids = useId();
  const [displayName, setDisplayName] = useState("");
  const [specialization, setSpecialization] = useState("");
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<unknown>(null);

  async function add(e: React.FormEvent) {
    e.preventDefault();
    setBusy(true);
    setError(null);
    try {
      await api.createDoctor({ displayName: displayName.trim(), specialization: specialization.trim() || undefined });
      setDisplayName("");
      setSpecialization("");
      onChanged();
    } catch (err) {
      setError(err);
    } finally {
      setBusy(false);
    }
  }

  const account = (doctorId: string) => users.find((u) => u.doctorProfileId === doctorId);

  return (
    <section className="panel" aria-labelledby={`${ids}-title`}>
      <h2 id={`${ids}-title`}>Doctors</h2>
      <ErrorBanner error={error} onDismiss={() => setError(null)} />
      <table className="table" aria-labelledby={`${ids}-title`}>
        <thead>
          <tr>
            <th scope="col">Doctor</th>
            <th scope="col">Specialization</th>
            <th scope="col">Appointment length</th>
            <th scope="col">Login</th>
            <th scope="col" className="actions-col">
              Schedule
            </th>
          </tr>
        </thead>
        <tbody>
          {doctors.map((doctor) => {
            const login = account(doctor.id);
            return (
              <tr key={doctor.id}>
                <th scope="row">{doctor.displayName}</th>
                <td>{doctor.specialization ?? <span className="muted">—</span>}</td>
                <td>{doctor.appointmentMinutes ? `${doctor.appointmentMinutes} min` : "—"}</td>
                <td>{login ? login.email : <span className="muted">No login yet</span>}</td>
                <td className="actions-col">
                  <Link href="/admin/schedules">Hours and leave</Link>
                </td>
              </tr>
            );
          })}
        </tbody>
      </table>
      <form className="row" onSubmit={add} style={{ alignItems: "flex-end" }}>
        <label className="field">
          <span>Doctor&apos;s name</span>
          <input value={displayName} onChange={(e) => setDisplayName(e.target.value)} required maxLength={150} placeholder="Dr. Anjali Rao" />
        </label>
        <label className="field">
          <span>Specialization (optional)</span>
          <input value={specialization} onChange={(e) => setSpecialization(e.target.value)} maxLength={120} />
        </label>
        <Button type="submit" variant="primary" busy={busy} disabled={!displayName.trim()}>
          Add doctor
        </Button>
      </form>
    </section>
  );
}

function StaffPanel({
  api,
  doctors,
  users,
  onChanged,
}: {
  api: ClinicApi;
  doctors: Doctor[];
  users: User[];
  onChanged: () => void;
}) {
  const ids = useId();
  const session = useSession();
  const [fullName, setFullName] = useState("");
  const [email, setEmail] = useState("");
  const [role, setRole] = useState<Role>("RECEPTIONIST");
  const [doctorId, setDoctorId] = useState("");
  const [password, setPassword] = useState("");
  const [busy, setBusy] = useState<string | null>(null);
  const [error, setError] = useState<unknown>(null);
  const [done, setDone] = useState<string | null>(null);
  const [disabling, setDisabling] = useState<User | null>(null);
  const cancelDisable = useCallback(() => setDisabling(null), []);

  const linked = new Set(users.map((u) => u.doctorProfileId).filter(Boolean));
  const freeDoctors = doctors.filter((d) => !linked.has(d.id));
  const chosenDoctor = doctorId || freeDoctors[0]?.id || "";

  async function add(e: React.FormEvent) {
    e.preventDefault();
    setBusy("add");
    setError(null);
    setDone(null);
    try {
      const created = await api.createUser({
        email: email.trim(),
        fullName: fullName.trim(),
        password,
        role,
        doctorProfileId: role === "DOCTOR" ? chosenDoctor : undefined,
      });
      setDone(`${created.fullName} can now sign in as ${ROLE_LABEL[created.role].toLowerCase()}. Share the password privately.`);
      setFullName("");
      setEmail("");
      setPassword("");
      onChanged();
    } catch (err) {
      setError(err);
    } finally {
      setBusy(null);
    }
  }

  async function disable(user: User) {
    setBusy(user.id);
    setError(null);
    setDone(null);
    try {
      await api.disableUser(user.id);
      setDone(`${user.fullName} can no longer sign in, and their open sessions were ended.`);
      onChanged();
    } catch (err) {
      setError(err);
    } finally {
      setBusy(null);
      setDisabling(null);
    }
  }

  return (
    <section className="panel" aria-labelledby={`${ids}-title`}>
      <h2 id={`${ids}-title`}>Staff accounts</h2>
      <ErrorBanner error={error} onDismiss={() => setError(null)} />
      {done ? <Notice>{done}</Notice> : null}
      <table className="table" aria-labelledby={`${ids}-title`}>
        <thead>
          <tr>
            <th scope="col">Name</th>
            <th scope="col">Email</th>
            <th scope="col">Role</th>
            <th scope="col">Status</th>
            <th scope="col" className="actions-col">
              Actions
            </th>
          </tr>
        </thead>
        <tbody>
          {users.map((user) => (
            <tr key={user.id}>
              <th scope="row">{user.fullName}</th>
              <td>{user.email}</td>
              <td>
                {ROLE_LABEL[user.role]}
                {user.doctorProfileId ? (
                  <span className="muted"> · {doctors.find((d) => d.id === user.doctorProfileId)?.displayName}</span>
                ) : null}
              </td>
              <td>{user.enabled ? "Active" : <span className="muted">Disabled</span>}</td>
              <td className="actions-col">
                {user.enabled && user.id !== session.user.id ? (
                  <Button size="sm" variant="ghost" busy={busy === user.id} onClick={() => setDisabling(user)} aria-label={`Disable ${user.fullName}`}>
                    Disable
                  </Button>
                ) : null}
              </td>
            </tr>
          ))}
        </tbody>
      </table>

      <form className="stack" onSubmit={add} aria-label="Add a staff account">
        <h3>Add a staff account</h3>
        <div className="row" style={{ alignItems: "flex-end" }}>
          <label className="field">
            <span>Full name</span>
            <input value={fullName} onChange={(e) => setFullName(e.target.value)} required maxLength={150} />
          </label>
          <label className="field">
            <span>Email</span>
            <input type="email" value={email} onChange={(e) => setEmail(e.target.value)} required maxLength={254} autoComplete="off" />
          </label>
          <label className="field">
            <span>Role</span>
            <select value={role} onChange={(e) => setRole(e.target.value as Role)}>
              <option value="RECEPTIONIST">Receptionist</option>
              <option value="DOCTOR">Doctor</option>
              <option value="ADMIN">Admin</option>
            </select>
          </label>
          {role === "DOCTOR" ? (
            <label className="field">
              <span>Doctor profile</span>
              {freeDoctors.length === 0 ? (
                <span className="hint">Every doctor already has a login. Add the doctor above first.</span>
              ) : (
                <select value={chosenDoctor} onChange={(e) => setDoctorId(e.target.value)}>
                  {freeDoctors.map((d) => (
                    <option key={d.id} value={d.id}>
                      {d.displayName}
                    </option>
                  ))}
                </select>
              )}
            </label>
          ) : null}
          <label className="field">
            <span>Initial password (12+ characters)</span>
            <input type="password" value={password} onChange={(e) => setPassword(e.target.value)} required minLength={12} maxLength={72} autoComplete="new-password" />
          </label>
        </div>
        <div className="row">
          <Button
            type="submit"
            variant="primary"
            busy={busy === "add"}
            disabled={password.length < 12 || (role === "DOCTOR" && !chosenDoctor)}
          >
            Create account
          </Button>
        </div>
      </form>

      <ConfirmDialog
        request={
          disabling
            ? {
                title: `Disable ${disabling.fullName}?`,
                message: "They will be signed out everywhere and cannot sign in again. Their past actions stay in the history.",
                confirmLabel: "Disable account",
                danger: true,
              }
            : null
        }
        busy={busy === disabling?.id}
        onConfirm={() => disabling && void disable(disabling)}
        onCancel={cancelDisable}
      />
    </section>
  );
}
