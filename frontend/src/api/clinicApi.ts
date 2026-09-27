import type { ApiClient } from "./client";
import type {
  Appointment,
  Clinic,
  Doctor,
  LoginResponse,
  Patient,
  PublicQueueStatus,
  QueueBoard,
  QueueEntry,
  User,
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
    queueNoShow: (entryId: string) => http.post<QueueEntry>(`/api/v1/queue-entries/${id(entryId)}/no-show`),

    publicStatus: (code: string) =>
      http.get<PublicQueueStatus>(`/api/v1/public/queue-status/${id(code)}`, { anonymous: true }),
  };
}

export type ClinicApi = ReturnType<typeof clinicApi>;
