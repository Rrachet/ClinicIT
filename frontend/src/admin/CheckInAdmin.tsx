"use client";

import { useCallback, useEffect, useId, useState } from "react";
import QRCode from "qrcode";

import type { ClinicApi } from "@/api/clinicApi";
import type { Doctor, VisitType } from "@/api/types";
import { useAuth } from "@/auth/AuthProvider";
import { AppHeader } from "@/ui/AppHeader";
import { Button } from "@/ui/Button";
import { ErrorBanner, Notice } from "@/ui/Feedback";
import { FullPageSpinner } from "@/ui/Spinner";
import { useAsync } from "@/ui/useAsync";

/** The public page a patient opens from the clinic's QR code. */
export function checkInUrl(code: string, origin: string = window.location.origin): string {
  return `${origin}/check-in/${code}`;
}

/**
 * Self check-in setup: the QR code to print for the waiting area, the visit types patients
 * choose from (and which doctors see each), and who is live right now.
 */
export function CheckInAdmin() {
  const { api } = useAuth();
  const data = useAsync(useCallback(() => Promise.all([api.clinic(), api.doctors(), api.visitTypes()]), [api]));

  if (!data.data) {
    return data.error ? (
      <main className="page">
        <ErrorBanner error={data.error} onRetry={data.reload} />
      </main>
    ) : (
      <FullPageSpinner label="Loading check-in" />
    );
  }
  const [clinic, doctors, visitTypes] = data.data;
  const anyLive = doctors.some((d) => d.live);

  return (
    <>
      <AppHeader title="Self check-in" clinicName={clinic.name} />
      <main className="page stack">
        {clinic.checkInCode ? <QrPoster clinicName={clinic.name} code={clinic.checkInCode} /> : null}
        {visitTypes.filter((t) => t.active).length === 0 ? (
          <Notice>Add at least one visit type below. Patients can only check in for an active visit type.</Notice>
        ) : null}
        {!anyLive ? <Notice>No doctor is live. Patients who scan the code are asked to wait until a doctor goes live.</Notice> : null}
        <DoctorsLive api={api} doctors={doctors} onChanged={data.reload} />
        <VisitTypes api={api} doctors={doctors} visitTypes={visitTypes} onChanged={data.reload} />
      </main>
    </>
  );
}

function QrPoster({ clinicName, code }: { clinicName: string; code: string }) {
  const url = checkInUrl(code);
  const [svg, setSvg] = useState<string | null>(null);
  const [copied, setCopied] = useState(false);
  useEffect(() => {
    let active = true;
    QRCode.toString(url, { type: "svg", margin: 1, errorCorrectionLevel: "M" }).then(
      (markup) => active && setSvg(markup),
      () => active && setSvg(null),
    );
    return () => {
      active = false;
    };
  }, [url]);

  return (
    <section className="panel qr-panel" aria-labelledby="qr-title">
      <div className="qr-poster">
        <p className="qr-clinic">{clinicName}</p>
        <h2 id="qr-title" className="qr-title">
          Scan to check in
        </h2>
        {svg ? (
          // Generated locally from our own URL; no user input reaches the markup.
          <div className="qr-code" role="img" aria-label="Check-in QR code" dangerouslySetInnerHTML={{ __html: svg }} />
        ) : (
          <div className="qr-code" />
        )}
        <p className="qr-steps">Enter your name and number, choose your visit, and follow your place in the queue on your phone.</p>
        <p className="qr-emergency">Emergency? Tell clinic staff now or call 112.</p>
      </div>
      <div className="qr-tools">
        <label className="field">
          <span>Check-in link</span>
          <input className="link-box" value={url} readOnly onFocus={(e) => e.target.select()} />
        </label>
        <div className="row">
          <Button variant="primary" onClick={() => window.print()}>
            Print poster
          </Button>
          <Button
            onClick={async () => {
              await navigator.clipboard?.writeText(url);
              setCopied(true);
            }}
          >
            {copied ? "Copied" : "Copy link"}
          </Button>
          <a className="btn btn-md" href={url} target="_blank" rel="noreferrer">
            Open check-in page
          </a>
        </div>
      </div>
    </section>
  );
}

function DoctorsLive({ api, doctors, onChanged }: { api: ClinicApi; doctors: Doctor[]; onChanged: () => void }) {
  const [busy, setBusy] = useState<string | null>(null);
  const [error, setError] = useState<unknown>(null);
  async function setLive(doctor: Doctor, live: boolean) {
    setBusy(doctor.id);
    setError(null);
    try {
      await api.setDuty(doctor.id, { live });
      onChanged();
    } catch (e) {
      setError(e);
    } finally {
      setBusy(null);
    }
  }
  return (
    <section className="panel" aria-labelledby="live-title">
      <h2 id="live-title">Doctors right now</h2>
      <p className="muted small">Doctors go live from their own screen. You can take a doctor offline if they forgot.</p>
      <ErrorBanner error={error} onDismiss={() => setError(null)} />
      {doctors.length === 0 ? (
        <p className="muted">No doctors yet. Add them on the Team page.</p>
      ) : (
        <ul className="plain-list">
          {doctors.map((d) => (
            <li key={d.id} className="row-between">
              <span>
                <strong>{d.displayName}</strong>
                {d.specialization ? <span className="muted"> · {d.specialization}</span> : null}
                {d.room ? <span className="muted"> · {d.room}</span> : null}
              </span>
              <span className="row">
                <span className={`live ${d.live ? "live-live" : "live-offline"}`}>
                  <span className="live-dot" aria-hidden />
                  {d.live ? "Live" : "Offline"}
                </span>
                {d.live ? (
                  <Button size="sm" busy={busy === d.id} onClick={() => void setLive(d, false)} aria-label={`Take ${d.displayName} offline`}>
                    Take offline
                  </Button>
                ) : null}
              </span>
            </li>
          ))}
        </ul>
      )}
    </section>
  );
}

interface Draft {
  id: string | null;
  name: string;
  description: string;
  anyDoctor: boolean;
  doctorIds: string[];
  active: boolean;
}

const EMPTY: Draft = { id: null, name: "", description: "", anyDoctor: false, doctorIds: [], active: true };

function VisitTypes({
  api,
  doctors,
  visitTypes,
  onChanged,
}: {
  api: ClinicApi;
  doctors: Doctor[];
  visitTypes: VisitType[];
  onChanged: () => void;
}) {
  const ids = useId();
  const [draft, setDraft] = useState<Draft>(EMPTY);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<unknown>(null);
  const [notice, setNotice] = useState<string | null>(null);
  const names = Object.fromEntries(doctors.map((d) => [d.id, d.displayName]));

  async function save(e: React.FormEvent) {
    e.preventDefault();
    setBusy(true);
    setError(null);
    const body = {
      name: draft.name.trim(),
      description: draft.description.trim() || undefined,
      anyDoctor: draft.anyDoctor,
      doctorIds: draft.anyDoctor ? [] : draft.doctorIds,
    };
    try {
      if (draft.id) await api.updateVisitType(draft.id, { ...body, active: draft.active });
      else await api.createVisitType(body);
      setNotice(`Saved "${body.name}".`);
      setDraft(EMPTY);
      onChanged();
    } catch (err) {
      setError(err);
    } finally {
      setBusy(false);
    }
  }

  const toggleDoctor = (id: string) =>
    setDraft((d) => ({ ...d, doctorIds: d.doctorIds.includes(id) ? d.doctorIds.filter((x) => x !== id) : [...d.doctorIds, id] }));

  return (
    <section className="panel" aria-labelledby={`${ids}-title`}>
      <h2 id={`${ids}-title`}>Visit types</h2>
      <p className="muted small">
        What patients choose from when they check in, and which doctors see each. The patient&apos;s choice decides the
        doctor; what they write is only passed to the doctor.
      </p>
      {notice ? <Notice>{notice}</Notice> : null}
      {visitTypes.length > 0 ? (
        <table className="table compact">
          <thead>
            <tr>
              <th scope="col">Visit type</th>
              <th scope="col">Seen by</th>
              <th scope="col">Shown</th>
              <th scope="col">
                <span className="sr-only">Actions</span>
              </th>
            </tr>
          </thead>
          <tbody>
            {visitTypes.map((t) => (
              <tr key={t.id}>
                <th scope="row">
                  {t.name}
                  {t.description ? <div className="muted small">{t.description}</div> : null}
                </th>
                <td>{t.anyDoctor ? "Any live doctor" : t.doctorIds.map((id) => names[id] ?? "?").join(", ")}</td>
                <td>{t.active ? "Yes" : "Hidden"}</td>
                <td>
                  <Button
                    size="sm"
                    aria-label={`Edit ${t.name}`}
                    onClick={() =>
                      setDraft({
                        id: t.id,
                        name: t.name,
                        description: t.description ?? "",
                        anyDoctor: t.anyDoctor,
                        doctorIds: t.doctorIds,
                        active: t.active,
                      })
                    }
                  >
                    Edit
                  </Button>
                </td>
              </tr>
            ))}
          </tbody>
        </table>
      ) : null}

      <form className="stack visit-type-form" onSubmit={save} aria-label={draft.id ? "Edit visit type" : "Add a visit type"}>
        <h3>{draft.id ? `Edit "${draft.name}"` : "Add a visit type"}</h3>
        <ErrorBanner error={error} onDismiss={() => setError(null)} />
        <label className="field">
          <span>Name patients see</span>
          <input
            value={draft.name}
            onChange={(e) => setDraft({ ...draft, name: e.target.value })}
            required
            maxLength={80}
            placeholder="e.g. Child (paediatrics)"
          />
        </label>
        <label className="field">
          <span>Short description (optional)</span>
          <input
            value={draft.description}
            onChange={(e) => setDraft({ ...draft, description: e.target.value })}
            maxLength={200}
            placeholder="e.g. For patients under 16"
          />
        </label>
        <label className="check">
          <input type="checkbox" checked={draft.anyDoctor} onChange={(e) => setDraft({ ...draft, anyDoctor: e.target.checked })} />
          Any live doctor can see this
        </label>
        {draft.anyDoctor ? null : (
          <fieldset className="doctor-picks">
            <legend>Doctors who see it</legend>
            {doctors.map((d) => (
              <label key={d.id} className="check">
                <input type="checkbox" checked={draft.doctorIds.includes(d.id)} onChange={() => toggleDoctor(d.id)} />
                {d.displayName}
                {d.specialization ? <span className="muted"> · {d.specialization}</span> : null}
              </label>
            ))}
          </fieldset>
        )}
        {draft.id ? (
          <label className="check">
            <input type="checkbox" checked={draft.active} onChange={(e) => setDraft({ ...draft, active: e.target.checked })} />
            Show on the check-in page
          </label>
        ) : null}
        <div className="row">
          <Button type="submit" variant="primary" busy={busy}>
            {draft.id ? "Save changes" : "Add visit type"}
          </Button>
          {draft.id ? (
            <Button type="button" onClick={() => setDraft(EMPTY)}>
              Cancel
            </Button>
          ) : null}
        </div>
      </form>
    </section>
  );
}
