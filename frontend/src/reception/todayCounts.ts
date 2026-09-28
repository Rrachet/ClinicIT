import type { Appointment, AppointmentStatus, QueueStatus } from "@/api/types";

export interface TodayCounts {
  total: number;
  /** Booked or confirmed, not here yet. */
  expected: number;
  /** Here: arrived, waiting, called, or stepped away (skipped). */
  here: number;
  withDoctor: number;
  completed: number;
  /** Cancelled or no-show. */
  missed: number;
}

/**
 * The front desk's at-a-glance numbers for today, from the appointment list with the live
 * queue status applied (it is newer than the list between refreshes).
 */
export function todayCounts(
  appointments: Appointment[],
  liveStatus: (appointmentId: string) => QueueStatus | undefined,
): TodayCounts {
  const counts: TodayCounts = { total: 0, expected: 0, here: 0, withDoctor: 0, completed: 0, missed: 0 };
  for (const appointment of appointments) {
    const status: AppointmentStatus = liveStatus(appointment.id) ?? appointment.status;
    counts.total++;
    switch (status) {
      case "BOOKED":
      case "CONFIRMED":
        counts.expected++;
        break;
      case "ARRIVED":
      case "WAITING":
      case "CALLED":
      case "SKIPPED":
        counts.here++;
        break;
      case "IN_CONSULTATION":
        counts.withDoctor++;
        break;
      case "COMPLETED":
        counts.completed++;
        break;
      case "CANCELLED":
      case "NO_SHOW":
        counts.missed++;
        break;
    }
  }
  return counts;
}
