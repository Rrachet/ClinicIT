// Mirrors the Spring Boot API DTOs (see docs/API.md). Dates are ISO strings:
// LocalDateTime values (scheduledAt) are clinic-local wall-clock time without an offset;
// Instant values (…At timestamps) are UTC.

export type Role = "ADMIN" | "RECEPTIONIST" | "DOCTOR";

export interface User {
  id: string;
  clinicId: string;
  email: string;
  fullName: string;
  role: Role;
  doctorProfileId: string | null;
  enabled: boolean;
}

export interface LoginResponse {
  accessToken: string;
  tokenType: "Bearer";
  expiresAt: string;
  user: User;
}

export interface Clinic {
  id: string;
  name: string;
  timezone: string;
  today: string;
}

export interface Doctor {
  id: string;
  clinicId: string;
  displayName: string;
  specialization: string | null;
}

export interface Patient {
  id: string;
  clinicId: string;
  fullName: string;
  phone: string;
  dateOfBirth: string | null;
}

export type AppointmentStatus =
  | "BOOKED"
  | "CONFIRMED"
  | "ARRIVED"
  | "WAITING"
  | "CALLED"
  | "IN_CONSULTATION"
  | "COMPLETED"
  | "CANCELLED"
  | "NO_SHOW"
  | "SKIPPED";

export interface Appointment {
  id: string;
  clinicId: string;
  patientId: string;
  patientName: string | null;
  doctorId: string;
  scheduledAt: string;
  status: AppointmentStatus;
  reasonSummary: string | null;
}

export type QueueStatus = "WAITING" | "CALLED" | "IN_CONSULTATION" | "COMPLETED" | "SKIPPED" | "NO_SHOW";

export interface QueueEntry {
  id: string;
  appointmentId: string;
  clinicId: string;
  doctorId: string;
  queueDate: string;
  tokenNumber: number;
  status: QueueStatus;
  checkedInAt: string | null;
  calledAt: string | null;
  consultationStartedAt: string | null;
  completedAt: string | null;
  skippedAt: string | null;
  version: number;
  statusCode: string;
}

export interface BoardEntry {
  id: string;
  appointmentId: string;
  tokenNumber: number;
  status: QueueStatus;
  patientName: string | null;
  patientsAhead: number | null;
  checkedInAt: string | null;
  calledAt: string | null;
  version: number;
  statusCode: string;
}

export interface QueueBoard {
  doctorId: string;
  queueDate: string;
  currentToken: number | null;
  waitingCount: number;
  entries: BoardEntry[];
}

export type QueueEventType =
  | "PATIENT_JOINED_QUEUE"
  | "PATIENT_CALLED"
  | "PATIENT_STARTED_CONSULTATION"
  | "PATIENT_COMPLETED"
  | "PATIENT_SKIPPED"
  | "PATIENT_REQUEUED"
  | "PATIENT_NO_SHOW";

/** Real-time contract, docs/REALTIME.md. No patient data. */
export interface QueueEvent {
  eventId: string;
  sequence: number;
  type: QueueEventType;
  occurredAt: string;
  clinicId: string;
  doctorId: string;
  queueDate: string;
  queueEntryId: string;
  appointmentId: string;
  tokenNumber: number;
  status: QueueStatus;
  previousStatus: QueueStatus | null;
  entryVersion: number;
}

export interface PublicQueueStatus {
  clinicName: string | null;
  doctorName: string | null;
  queueDate: string;
  tokenNumber: number;
  status: QueueStatus;
  currentToken: number | null;
  patientsAhead: number;
}

export interface ApiErrorBody {
  timestamp?: string;
  status: number;
  code: string;
  message: string;
  path?: string;
}
