"use client";

import { useEffect, useId, useState } from "react";
import type { ClinicApi } from "@/api/clinicApi";
import type { Clinic, Doctor, Patient } from "@/api/types";
import { Button } from "@/ui/Button";
import { ErrorBanner } from "@/ui/Feedback";
import { clinicNow } from "@/ui/format";
import { book, type BookingResult } from "./workflows";

/**
 * Fast front-desk booking: find (or register) the patient, pick doctor and time, and
 * optionally check them straight into the queue when they are standing at the desk.
 */
export function NewAppointmentPanel({
  api,
  clinic,
  doctors,
  onBooked,
}: {
  api: ClinicApi;
  clinic: Clinic;
  doctors: Doctor[];
  onBooked: (result: BookingResult) => void;
}) {
  const ids = useId();
  const [query, setQuery] = useState("");
  const [search, setSearch] = useState<{ query: string; results: Patient[] } | null>(null);
  const [patient, setPatient] = useState<Patient | null>(null);
  const [registering, setRegistering] = useState(false);
  const [newName, setNewName] = useState("");
  const [newPhone, setNewPhone] = useState("");
  const [chosenDoctorId, setDoctorId] = useState("");
  const doctorId = chosenDoctorId || doctors[0]?.id || "";
  const [time, setTime] = useState(() => clinicNow(clinic.timezone).slice(11, 16));
  const [reason, setReason] = useState("");
  const [checkInNow, setCheckInNow] = useState(true);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<unknown>(null);

  // Debounced name search; the backend returns at most 20 matches, from this clinic only.
  const term = query.trim();
  const wantsSearch = term.length >= 2 && !patient;
  useEffect(() => {
    if (!wantsSearch) return;
    let active = true;
    const timer = setTimeout(() => {
      api.searchPatients(term).then(
        (results) => active && setSearch({ query: term, results }),
        (e: unknown) => active && setError(e),
      );
    }, 250);
    return () => {
      active = false;
      clearTimeout(timer);
    };
  }, [api, term, wantsSearch]);
  const results = wantsSearch && search?.query === term ? search.results : null;
  const searching = wantsSearch && search?.query !== term;

  const reset = () => {
    setQuery("");
    setPatient(null);
    setSearch(null);
    setReason("");
    setRegistering(false);
    setNewName("");
    setNewPhone("");
    setTime(clinicNow(clinic.timezone).slice(11, 16));
  };

  async function register() {
    setBusy(true);
    setError(null);
    try {
      const created = await api.registerPatient({ fullName: newName.trim(), phone: newPhone.trim() });
      setPatient(created);
      setRegistering(false);
    } catch (e) {
      setError(e);
    } finally {
      setBusy(false);
    }
  }

  async function submit(e: React.FormEvent) {
    e.preventDefault();
    if (!patient || !doctorId) return;
    setBusy(true);
    setError(null);
    try {
      const result = await book(api, {
        patientId: patient.id,
        doctorId,
        scheduledAt: `${clinic.today}T${time}`,
        reasonSummary: reason,
        checkInNow,
      });
      onBooked(result);
      reset();
    } catch (err) {
      setError(err);
    } finally {
      setBusy(false);
    }
  }

  return (
    <section className="panel" aria-labelledby={`${ids}-title`}>
      <h2 id={`${ids}-title`}>New appointment</h2>
      <ErrorBanner error={error} onDismiss={() => setError(null)} />

      {patient ? (
        <div className="picked">
          <div>
            <strong>{patient.fullName}</strong>
            <div className="muted">{patient.phone}</div>
          </div>
          <button type="button" className="link-button" onClick={() => setPatient(null)}>
            Change
          </button>
        </div>
      ) : registering ? (
        <div className="stack">
          <label className="field">
            <span>Full name</span>
            <input value={newName} onChange={(e) => setNewName(e.target.value)} autoFocus required maxLength={150} />
          </label>
          <label className="field">
            <span>Phone</span>
            <input value={newPhone} onChange={(e) => setNewPhone(e.target.value)} required maxLength={30} inputMode="tel" />
          </label>
          <div className="row">
            <Button variant="primary" onClick={register} busy={busy} disabled={!newName.trim() || !newPhone.trim()}>
              Register patient
            </Button>
            <Button variant="ghost" onClick={() => setRegistering(false)}>
              Back to search
            </Button>
          </div>
        </div>
      ) : (
        <div className="stack">
          <label className="field">
            <span>Find patient</span>
            <input
              value={query}
              onChange={(e) => setQuery(e.target.value)}
              placeholder="Type a name"
              autoComplete="off"
              aria-describedby={`${ids}-search-hint`}
            />
          </label>
          <span id={`${ids}-search-hint`} className="hint">
            {searching ? "Searching…" : term.length < 2 ? "At least 2 letters" : ""}
          </span>
          {results && results.length > 0 ? (
            <ul className="results" aria-label="Matching patients">
              {results.map((p) => (
                <li key={p.id}>
                  <button type="button" onClick={() => setPatient(p)}>
                    <strong>{p.fullName}</strong> <span className="muted">{p.phone}</span>
                  </button>
                </li>
              ))}
            </ul>
          ) : results ? (
            <p className="muted">No patient found.</p>
          ) : null}
          <Button
            variant="ghost"
            onClick={() => {
              setRegistering(true);
              setNewName(query.trim());
            }}
          >
            + Register new patient
          </Button>
        </div>
      )}

      <form className="stack" onSubmit={submit}>
        <label className="field">
          <span>Doctor</span>
          <select value={doctorId} onChange={(e) => setDoctorId(e.target.value)} required>
            {doctors.map((d) => (
              <option key={d.id} value={d.id}>
                {d.displayName}
              </option>
            ))}
          </select>
        </label>
        <label className="field">
          <span>Time today</span>
          <input type="time" value={time} onChange={(e) => setTime(e.target.value)} required />
        </label>
        <label className="field">
          <span>Reason (optional)</span>
          <input value={reason} onChange={(e) => setReason(e.target.value)} maxLength={500} />
        </label>
        <label className="check">
          <input type="checkbox" checked={checkInNow} onChange={(e) => setCheckInNow(e.target.checked)} />
          Patient is here now — check in and add to queue
        </label>
        <Button type="submit" variant="primary" size="lg" busy={busy} disabled={!patient || !doctorId}>
          {checkInNow ? "Book and add to queue" : "Book appointment"}
        </Button>
      </form>
    </section>
  );
}
