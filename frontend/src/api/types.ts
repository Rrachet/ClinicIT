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
  /** Length of one appointment slot (minutes). */
  appointmentMinutes?: number;
}

export type DayOfWeek = "MONDAY" | "TUESDAY" | "WEDNESDAY" | "THURSDAY" | "FRIDAY" | "SATURDAY" | "SUNDAY";

/** Clinic-local times "HH:mm[:ss]". A day without an entry is a day off. */
export interface WorkingDay {
  dayOfWeek: DayOfWeek;
  start: string;
  end: string;
  breakStart: string | null;
  breakEnd: string | null;
}

export interface TimeOff {
  id: string;
  startsAt: string;
  endsAt: string;
  reason: string | null;
}

export interface DoctorSchedule {
  doctorId: string;
  appointmentMinutes: number;
  weeklyHours: WorkingDay[];
  timeOff: TimeOff[];
}

export type Unavailable =
  | "DAY_OFF"
  | "OUTSIDE_HOURS"
  | "ON_BREAK"
  | "TIME_OFF"
  | "IN_THE_PAST"
  | "SLOT_TAKEN"
  | "NOT_WORKING_TODAY";

export interface Availability {
  doctorId: string;
  date: string;
  /** false: the doctor has no weekly hours yet, so any time can be booked. */
  scheduled: boolean;
  appointmentMinutes: number;
  hours: { start: string; end: string; breakStart: string | null; breakEnd: string | null } | null;
  timeOff: { startsAt: string; endsAt: string }[];
  slots: { start: string; end: string; available: boolean; reason: Unavailable | null }[];
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
  /** Booked for "now" at the desk; holds no slot. */
  walkIn?: boolean;
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
  /** Approximate wait before being called, while WAITING (Phase 8). A range, never a promise. */
  estimatedWait: { estimatedWaitMinutes: number; lowerBoundMinutes: number; upperBoundMinutes: number } | null;
}

export interface ApiErrorBody {
  timestamp?: string;
  status: number;
  code: string;
  message: string;
  path?: string;
}

export type NotificationType = "APPOINTMENT_CONFIRMED" | "PATIENT_JOINED_QUEUE" | "PATIENT_NEAR_TURN" | "PATIENT_CALLED";
export type NotificationStatus = "PENDING" | "SENT" | "FAILED";

/** A message sent (or being sent) to a patient. recipient is masked by the server. */
export interface PatientNotification {
  id: string;
  appointmentId: string;
  type: NotificationType;
  channel: "SMS" | "WHATSAPP" | "EMAIL";
  recipient: string;
  body: string;
  status: NotificationStatus;
  attempts: number;
  maxAttempts: number;
  lastError: string | null;
  createdAt: string;
  sentAt: string | null;
  nextAttemptAt: string | null;
  expiresAt: string;
}

// Operational analytics (Phase 7). Durations are seconds; null means "no data", never 0.

export interface DailySummary {
  date: string;
  timezone: string;
  doctorId: string | null;
  scheduledAppointments: number;
  patients: number;
  completedConsultations: number;
  averageWaitSeconds: number | null;
  medianWaitSeconds: number | null;
  averageConsultationSeconds: number | null;
  averageDelaySeconds: number | null;
  cancellations: number;
  noShows: number;
  cancellationRate: number | null;
  noShowRate: number | null;
  currentQueueLength: number;
}

export interface WaitTimes {
  date: string;
  doctorId: string | null;
  calledPatients: number;
  averageWaitSeconds: number | null;
  medianWaitSeconds: number | null;
  p90WaitSeconds: number | null;
  maxWaitSeconds: number | null;
  byHour: { hour: number; calledPatients: number; averageWaitSeconds: number | null; medianWaitSeconds: number | null }[];
}

export interface DoctorStats {
  doctorId: string;
  doctorName: string;
  patientsCalled: number;
  patientsHandled: number;
  averageWaitSeconds: number | null;
  averageConsultationSeconds: number | null;
  consultationSeconds: number | null;
  utilization: number | null;
  /** Minutes scheduled to see patients that day; null when the doctor has no schedule. */
  scheduledMinutes?: number | null;
  scheduledUtilization?: number | null;
}

export interface DoctorAnalytics {
  date: string;
  doctors: DoctorStats[];
}

export interface QueueAnalytics {
  date: string;
  doctorId: string | null;
  currentQueueLength: number;
  byHour: { hour: number; joined: number; completed: number; queueLength: number }[];
}

// Wait-time estimates (Phase 8). Advisory only; they never change the order patients are called in.

export interface WaitEstimate {
  queueEntryId: string;
  tokenNumber: number;
  estimatedWaitMinutes: number;
  lowerBoundMinutes: number;
  upperBoundMinutes: number;
  /** MODEL: the ML service; BASELINE: patients ahead × average consultation (fallback). */
  source: "MODEL" | "BASELINE";
  modelVersion: string;
  fallbackReason: string | null;
}

export interface WaitEstimates {
  doctorId: string;
  queueDate: string;
  entries: WaitEstimate[];
}
