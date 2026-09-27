import type { AppointmentStatus, QueueStatus } from "@/api/types";
import { statusLabel } from "./format";

export function StatusBadge({ status }: { status: AppointmentStatus | QueueStatus }) {
  return <span className={`badge badge-${status.toLowerCase().replace("_", "-")}`}>{statusLabel(status)}</span>;
}
