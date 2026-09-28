import type { ClinicApi } from "@/api/clinicApi";
import type { Appointment, QueueEntry } from "@/api/types";

export interface BookingRequest {
  patientId: string;
  doctorId: string;
  /** Clinic-local "YYYY-MM-DDTHH:mm". Ignored for a walk-in (checkInNow). */
  scheduledAt?: string;
  reasonSummary?: string;
  /**
   * The patient is at the desk now: a walk-in booked for the current time (the server's
   * clock), then confirmed, checked in and queued in one go.
   */
  checkInNow: boolean;
}

export interface BookingResult {
  appointment: Appointment;
  queueEntry?: QueueEntry;
}

/**
 * Books an appointment and, for a patient standing at the desk, walks it through the
 * existing steps (confirm → arrive → join queue). Each step is a normal API call the
 * backend validates; if one fails, the steps already done stay done and the error is shown.
 */
export async function book(api: ClinicApi, request: BookingRequest): Promise<BookingResult> {
  const reasonSummary = request.reasonSummary?.trim() || undefined;
  let appointment = request.checkInNow
    ? await api.createAppointment({ patientId: request.patientId, doctorId: request.doctorId, walkIn: true, reasonSummary })
    : await api.createAppointment({
        patientId: request.patientId,
        doctorId: request.doctorId,
        scheduledAt: withSeconds(request.scheduledAt ?? ""),
        reasonSummary,
      });
  if (!request.checkInNow) return { appointment };

  appointment = await api.confirmAppointment(appointment.id);
  appointment = await api.arrive(appointment.id);
  const queueEntry = await api.joinQueue(appointment.id);
  return { appointment, queueEntry };
}

function withSeconds(localDateTime: string): string {
  return localDateTime.length === 16 ? `${localDateTime}:00` : localDateTime;
}

/** The link a patient opens to follow their place in the queue (no login). */
export function statusLink(statusCode: string, origin: string = window.location.origin): string {
  return `${origin}/status/${statusCode}`;
}
