import type { ClinicApi } from "@/api/clinicApi";
import type { Appointment } from "@/api/types";
import type { QueueItem } from "@/queue/queueStore";
import type { ConfirmRequest } from "@/ui/ConfirmDialog";

/**
 * Which buttons the front desk sees for a row. These are presentation hints that mirror the
 * backend's state machine (docs/QUEUE_ENGINE.md); the server still decides every request
 * and its error is shown if it disagrees. No rule here is enforced only in the browser.
 */
export type ReceptionActionKey =
  | "confirm"
  | "arrive"
  | "join"
  | "skip"
  | "requeue"
  | "queue-no-show"
  | "appointment-no-show"
  | "cancel";

export interface ReceptionAction {
  key: ReceptionActionKey;
  label: string;
  primary?: boolean;
  confirm?: ConfirmRequest;
}

export function actionsFor(appointment: Appointment, item: QueueItem | undefined): ReceptionAction[] {
  const who = appointment.patientName ?? "this patient";
  switch (appointment.status) {
    case "BOOKED":
      return [{ key: "confirm", label: "Confirm", primary: true }, cancel(who)];
    case "CONFIRMED":
      return [{ key: "arrive", label: "Mark arrived", primary: true }, appointmentNoShow(who), cancel(who)];
    case "ARRIVED":
      return [{ key: "join", label: "Add to queue", primary: true }, appointmentNoShow(who)];
    case "WAITING":
    case "CALLED":
      return item ? [{ key: "skip", label: "Skip" }] : [];
    case "SKIPPED":
      return item
        ? [
            { key: "requeue", label: "Back in queue", primary: true },
            {
              key: "queue-no-show",
              label: "No-show",
              confirm: {
                title: "Mark as no-show?",
                message: `${who} will be removed from today's queue. This cannot be undone.`,
                confirmLabel: "Mark no-show",
                danger: true,
              },
            },
          ]
        : [];
    default:
      return [];
  }
}

function cancel(who: string): ReceptionAction {
  return {
    key: "cancel",
    label: "Cancel",
    confirm: {
      title: "Cancel appointment?",
      message: `The appointment for ${who} will be cancelled. This cannot be undone.`,
      confirmLabel: "Cancel appointment",
      danger: true,
    },
  };
}

function appointmentNoShow(who: string): ReceptionAction {
  return {
    key: "appointment-no-show",
    label: "No-show",
    confirm: {
      title: "Mark as no-show?",
      message: `${who} will be recorded as not attending. This cannot be undone.`,
      confirmLabel: "Mark no-show",
      danger: true,
    },
  };
}

/** Runs one action through the matching endpoint. */
export async function perform(api: ClinicApi, key: ReceptionActionKey, appointment: Appointment, item?: QueueItem) {
  const entry = () => {
    if (!item) throw new Error("No queue entry for this appointment");
    return item.id;
  };
  switch (key) {
    case "confirm":
      return api.confirmAppointment(appointment.id);
    case "arrive":
      return api.arrive(appointment.id);
    case "join":
      return api.joinQueue(appointment.id);
    case "cancel":
      return api.cancelAppointment(appointment.id);
    case "appointment-no-show":
      return api.appointmentNoShow(appointment.id);
    case "skip":
      return api.skip(entry());
    case "requeue":
      return api.requeue(entry());
    case "queue-no-show":
      return api.queueNoShow(entry());
  }
}
