"use client";

import { useCallback, useEffect, useMemo, useRef, useState } from "react";
import type { Appointment, Doctor } from "@/api/types";
import { useAuth, useSession } from "@/auth/AuthProvider";
import { itemForAppointment } from "@/queue/queueStore";
import { useLiveQueue } from "@/queue/useLiveQueue";
import { AppHeader } from "@/ui/AppHeader";
import { ConfirmDialog, type ConfirmRequest } from "@/ui/ConfirmDialog";
import { ErrorBanner, Notice } from "@/ui/Feedback";
import { FullPageSpinner } from "@/ui/Spinner";
import { useAsync } from "@/ui/useAsync";
import { AppointmentTable } from "./AppointmentTable";
import { NewAppointmentPanel } from "./NewAppointmentPanel";
import { NowServing } from "./NowServing";
import { StatusLinkDialog, type LinkTarget } from "./StatusLinkDialog";
import { perform, type ReceptionAction } from "./actions";

/** "Today's Clinic": the front desk's single working screen. */
export function ReceptionConsole() {
  const { api } = useAuth();
  const session = useSession();
  const [actionError, setActionError] = useState<unknown>(null);
  const [busyKey, setBusyKey] = useState<string | null>(null);
  const [pending, setPending] = useState<{ appointment: Appointment; action: ReceptionAction } | null>(null);
  const [link, setLink] = useState<LinkTarget | null>(null);
  const [doctorFilter, setDoctorFilter] = useState<string>("all");
  const [notice, setNotice] = useState<string | null>(null);

  const setup = useAsync(useCallback(() => Promise.all([api.clinic(), api.doctors()]), [api]));
  const clinic = setup.data?.[0] ?? null;
  const doctors = useMemo(() => setup.data?.[1] ?? [], [setup.data]);

  const today = clinic?.today;
  const appointmentList = useAsync(useCallback(() => (today ? api.appointments(today) : Promise.resolve([])), [api, today]));
  const appointments = appointmentList.data ?? [];
  const reloadAppointments = appointmentList.reload;

  // Live queue changes also change appointment statuses: refresh the list, debounced.
  const refreshTimer = useRef<ReturnType<typeof setTimeout> | undefined>(undefined);
  const scheduleRefresh = useCallback(() => {
    clearTimeout(refreshTimer.current);
    refreshTimer.current = setTimeout(reloadAppointments, 300);
  }, [reloadAppointments]);
  useEffect(() => () => clearTimeout(refreshTimer.current), []);

  const doctorIds = useMemo(() => doctors.map((d) => d.id), [doctors]);
  const live = useLiveQueue({
    doctorIds,
    destination: clinic ? `/topic/clinic/${session.user.clinicId}/queue` : null,
    onChange: scheduleRefresh,
  });

  async function run(appointment: Appointment, action: ReceptionAction) {
    const key = `${appointment.id}:${action.key}`;
    setBusyKey(key);
    setActionError(null);
    try {
      const result = await perform(api, action.key, appointment, itemForAppointment(live.queue, appointment.id));
      if (action.key === "join" && result && "tokenNumber" in result) {
        setNotice(`${appointment.patientName ?? "Patient"} is token #${result.tokenNumber}. The queue link is being sent to them.`);
      }
      reloadAppointments();
      if (action.key === "join") await live.reload();
    } catch (e) {
      setActionError(e);
    } finally {
      setBusyKey(null);
      setPending(null);
    }
  }

  function onAction(appointment: Appointment, action: ReceptionAction) {
    if (action.confirm) setPending({ appointment, action });
    else void run(appointment, action);
  }

  async function callNext(doctor: Doctor) {
    setBusyKey(`call:${doctor.id}`);
    setActionError(null);
    try {
      const called = await api.callNext(doctor.id);
      setNotice(`Called token #${called.tokenNumber} for ${doctor.displayName}`);
    } catch (e) {
      setActionError(e);
    } finally {
      setBusyKey(null);
    }
  }

  if (setup.error && !clinic) {
    return (
      <main className="page">
        <ErrorBanner error={setup.error} onRetry={setup.reload} />
      </main>
    );
  }
  if (!clinic) return <FullPageSpinner label="Loading today's clinic" />;

  const shown = doctorFilter === "all" ? appointments : appointments.filter((a) => a.doctorId === doctorFilter);
  const confirmRequest: ConfirmRequest | null = pending?.action.confirm ?? null;

  return (
    <div className="shell">
      <AppHeader title="Today's Clinic" clinicName={clinic.name} day={clinic.today} feed={live.feedStatus} />
      <main className="page">
        <ErrorBanner error={actionError} onDismiss={() => setActionError(null)} />
        <ErrorBanner error={appointmentList.error} onRetry={reloadAppointments} />
        <ErrorBanner error={live.error} onRetry={() => void live.reload()} />
        {notice ? <Notice>{notice}</Notice> : null}

        {doctors.length === 0 ? (
          <Notice>No doctors are set up for this clinic yet. An admin can add them.</Notice>
        ) : (
          <NowServing
            doctors={doctors}
            queue={live.queue}
            busyDoctorId={busyKey?.startsWith("call:") ? busyKey.slice(5) : null}
            onCallNext={callNext}
          />
        )}

        <div className="reception-grid">
          <section className="panel" aria-labelledby="appointments-title">
            <div className="panel-head">
              <h2 id="appointments-title">Appointments</h2>
              <label className="inline-field">
                <span className="sr-only">Filter by doctor</span>
                <select value={doctorFilter} onChange={(e) => setDoctorFilter(e.target.value)}>
                  <option value="all">All doctors</option>
                  {doctors.map((d) => (
                    <option key={d.id} value={d.id}>
                      {d.displayName}
                    </option>
                  ))}
                </select>
              </label>
            </div>
            <AppointmentTable
              appointments={shown}
              doctors={doctors}
              queue={live.queue}
              busyKey={busyKey}
              onAction={onAction}
              onShowLink={(appointmentId, code, token) => setLink({ appointmentId, code, token })}
            />
          </section>

          {doctors.length > 0 ? (
            <NewAppointmentPanel
              api={api}
              clinic={clinic}
              doctors={doctors}
              onBooked={(result) => {
                reloadAppointments();
                if (result.queueEntry) {
                  void live.reload();
                  setNotice(
                    `${result.appointment.patientName ?? "Patient"} is token #${result.queueEntry.tokenNumber}. The queue link is being sent to them.`,
                  );
                } else {
                  setNotice(`Booked ${result.appointment.patientName ?? "patient"}`);
                }
              }}
            />
          ) : null}
        </div>
      </main>

      <ConfirmDialog
        request={confirmRequest}
        busy={pending ? busyKey === `${pending.appointment.id}:${pending.action.key}` : false}
        onCancel={() => setPending(null)}
        onConfirm={() => pending && void run(pending.appointment, pending.action)}
      />
      <StatusLinkDialog api={api} link={link} onClose={() => setLink(null)} />
    </div>
  );
}
