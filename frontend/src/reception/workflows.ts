import type { ClinicApi } from "@/api/clinicApi";
import type { Appointment, QueueEntry } from "@/api/types";

export interface BookingRequest {
  patientId: string;
  doctorId: string;
  /** Clinic-local "YYYY-MM-DDTHH:mm". */
  scheduledAt: string;
  reasonSummary?: string;
  /** The patient is at the desk now: confirm, check in and queue in one go. */
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
  let appointment = await api.createAppointment({
    patientId: request.patientId,
    doctorId: request.doctorId,
    scheduledAt: request.scheduledAt.length === 16 ? `${request.scheduledAt}:00` : request.scheduledAt,
    reasonSummary: request.reasonSummary?.trim() || undefined,
  });
  if (!request.checkInNow) return { appointment };

  appointment = await api.confirmAppointment(appointment.id);
  appointment = await api.arrive(appointment.id);
  const queueEntry = await api.joinQueue(appointment.id);
  return { appointment, queueEntry };
}

/** The link a patient opens to follow their place in the queue (no login). */
export function statusLink(statusCode: string, origin: string = window.location.origin): string {
  return `${origin}/status/${statusCode}`;
}
