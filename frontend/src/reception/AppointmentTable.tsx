"use client";

import type { Appointment, Doctor, NoShowRisk } from "@/api/types";
import { itemForAppointment, type QueueState } from "@/queue/queueStore";
import { Button } from "@/ui/Button";
import { EmptyState } from "@/ui/Feedback";
import { StatusBadge } from "@/ui/StatusBadge";
import { timeOf } from "@/ui/format";
import { actionsFor, type ReceptionAction } from "./actions";

export function AppointmentTable({
  appointments,
  doctors,
  queue,
  busyKey,
  onAction,
  onShowLink,
  risks = {},
}: {
  appointments: Appointment[];
  doctors: Doctor[];
  queue: QueueState;
  busyKey: string | null;
  onAction: (appointment: Appointment, action: ReceptionAction) => void;
  onShowLink: (appointmentId: string, statusCode: string, token: number) => void;
  /** Advisory no-show flags by appointment id; only "Elevated" is shown. */
  risks?: Record<string, NoShowRisk>;
}) {
  if (appointments.length === 0) {
    return <EmptyState title="No appointments today yet" hint="Book one with the form on the right." />;
  }
  const doctorName = (id: string) => doctors.find((d) => d.id === id)?.displayName ?? "—";

  return (
    <table className="table">
      <thead>
        <tr>
          <th scope="col">Time</th>
          <th scope="col">Token</th>
          <th scope="col">Patient</th>
          <th scope="col">Doctor</th>
          <th scope="col">Status</th>
          <th scope="col" className="actions-col">
            Actions
          </th>
        </tr>
      </thead>
      <tbody>
        {appointments.map((appointment) => {
          const item = itemForAppointment(queue, appointment.id);
          // The live queue entry is newer than the appointment list between refreshes.
          const status = item?.status ?? appointment.status;
          const actions = actionsFor({ ...appointment, status }, item);
          return (
            <tr key={appointment.id} data-testid={`appointment-${appointment.id}`}>
              <td className="mono">{timeOf(appointment.scheduledAt)}</td>
              <td>{item ? <span className="token token-sm">#{item.tokenNumber}</span> : <span className="muted">—</span>}</td>
              <td>
                {appointment.patientName ?? "—"}
                {risks[appointment.id]?.level === "ELEVATED" && (status === "BOOKED" || status === "CONFIRMED") ? (
                  <>
                    {" "}
                    <span className="badge badge-risk" title={`${risks[appointment.id].reason}. Advisory only: consider a reminder call.`}>
                      No-show risk: Elevated
                    </span>
                  </>
                ) : null}
              </td>
              <td>{doctorName(appointment.doctorId)}</td>
              <td>
                <StatusBadge status={status} />
              </td>
              <td className="actions-col">
                <div className="row-actions">
                  {actions.map((action) => (
                    <Button
                      key={action.key}
                      size="sm"
                      variant={action.primary ? "primary" : action.confirm ? "ghost" : "secondary"}
                      busy={busyKey === `${appointment.id}:${action.key}`}
                      onClick={() => onAction(appointment, action)}
                      aria-label={`${action.label}: ${appointment.patientName ?? "patient"}`}
                    >
                      {action.label}
                    </Button>
                  ))}
                  {item?.statusCode && (item.status === "WAITING" || item.status === "CALLED") ? (
                    <Button
                      size="sm"
                      variant="ghost"
                      onClick={() => onShowLink(appointment.id, item.statusCode!, item.tokenNumber)}
                      aria-label={`Messages: ${appointment.patientName ?? "patient"}`}
                    >
                      Messages
                    </Button>
                  ) : null}
                </div>
              </td>
            </tr>
          );
        })}
      </tbody>
    </table>
  );
}
