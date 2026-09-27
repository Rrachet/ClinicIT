import type { ApiClient } from "./client";
import type {
  Appointment,
  Clinic,
  DailySummary,
  Doctor,
  DoctorAnalytics,
  LoginResponse,
  Patient,
  PatientNotification,
  PublicQueueStatus,
  QueueAnalytics,
  QueueBoard,
  QueueEntry,
  User,
  WaitEstimates,
  WaitTimes,
} from "./types";

/** Typed wrappers around every backend endpoint the UI uses. No logic beyond HTTP. */
export function clinicApi(http: ApiClient) {
  const id = encodeURIComponent;
  return {
    login: (email: string, password: string) =>
      http.post<LoginResponse>("/api/v1/auth/login", { body: { email, password }, anonymous: true }),
    logout: () => http.post<void>("/api/v1/auth/logout"),
    me: () => http.get<User>("/api/v1/auth/me"),

    clinic: () => http.get<Clinic>("/api/v1/clinic"),
    doctors: () => http.get<Doctor[]>("/api/v1/doctors"),

    searchPatients: (name: string) => http.get<Patient[]>("/api/v1/patients", { query: { name } }),
    registerPatient: (patient: { fullName: string; phone: string; dateOfBirth?: string | null }) =>
      http.post<Patient>("/api/v1/patients", { body: patient }),

    appointments: (date: string, doctorId?: string) =>
      http.get<Appointment[]>("/api/v1/appointments", { query: { date, doctorId } }),
    appointment: (appointmentId: string) => http.get<Appointment>(`/api/v1/appointments/${id(appointmentId)}`),
    createAppointment: (body: { patientId: string; doctorId: string; scheduledAt: string; reasonSummary?: string }) =>
      http.post<Appointment>("/api/v1/appointments", { body }),
    confirmAppointment: (appointmentId: string) =>
      http.post<Appointment>(`/api/v1/appointments/${id(appointmentId)}/confirm`),
    arrive: (appointmentId: string) => http.post<Appointment>(`/api/v1/appointments/${id(appointmentId)}/arrive`),
    cancelAppointment: (appointmentId: string) =>
      http.post<Appointment>(`/api/v1/appointments/${id(appointmentId)}/cancel`),
    appointmentNoShow: (appointmentId: string) =>
      http.post<Appointment>(`/api/v1/appointments/${id(appointmentId)}/no-show`),

    joinQueue: (appointmentId: string) => http.post<QueueEntry>("/api/v1/queue-entries", { body: { appointmentId } }),
    board: (doctorId?: string) => http.get<QueueBoard>("/api/v1/queues/today", { query: { doctorId } }),
    callNext: (doctorId?: string) =>
      http.post<QueueEntry>("/api/v1/queues/call-next", { body: doctorId ? { doctorId } : {} }),
    startConsultation: (entryId: string) => http.post<QueueEntry>(`/api/v1/queue-entries/${id(entryId)}/start`),
    completeConsultation: (entryId: string) => http.post<QueueEntry>(`/api/v1/queue-entries/${id(entryId)}/complete`),
    skip: (entryId: string) => http.post<QueueEntry>(`/api/v1/queue-entries/${id(entryId)}/skip`),
    requeue: (entryId: string) => http.post<QueueEntry>(`/api/v1/queue-entries/${id(entryId)}/requeue`),
    waitEstimates: (doctorId: string) =>
      http.get<WaitEstimates>("/api/v1/queues/today/wait-estimates", { query: { doctorId } }),
    queueNoShow: (entryId: string) => http.post<QueueEntry>(`/api/v1/queue-entries/${id(entryId)}/no-show`),

    notifications: (appointmentId: string) =>
      http.get<PatientNotification[]>("/api/v1/notifications", { query: { appointmentId } }),
    retryNotification: (notificationId: string) =>
      http.post<PatientNotification>(`/api/v1/notifications/${id(notificationId)}/retry`),

    analyticsToday: (date?: string, doctorId?: string) =>
      http.get<DailySummary>("/api/v1/analytics/today", { query: { date, doctorId } }),
    analyticsWaitTimes: (date?: string, doctorId?: string) =>
      http.get<WaitTimes>("/api/v1/analytics/wait-times", { query: { date, doctorId } }),
    analyticsDoctors: (date?: string) => http.get<DoctorAnalytics>("/api/v1/analytics/doctors", { query: { date } }),
    analyticsQueue: (date?: string, doctorId?: string) =>
      http.get<QueueAnalytics>("/api/v1/analytics/queue", { query: { date, doctorId } }),

    publicStatus: (code: string) =>
      http.get<PublicQueueStatus>(`/api/v1/public/queue-status/${id(code)}`, { anonymous: true }),
  };
}

export type ClinicApi = ReturnType<typeof clinicApi>;
