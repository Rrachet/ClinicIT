import type { Appointment, BoardEntry, Clinic, Doctor, QueueBoard, QueueEvent, User } from "@/api/types";
import type { Session } from "@/auth/session";

export const CLINIC: Clinic = { id: "clinic-1", name: "City Clinic", timezone: "Asia/Kolkata", today: "2026-03-10" };
export const SHARMA: Doctor = { id: "doc-sharma", clinicId: CLINIC.id, displayName: "Dr. Sharma", specialization: null };
export const MEHTA: Doctor = { id: "doc-mehta", clinicId: CLINIC.id, displayName: "Dr. Mehta", specialization: null };

export function user(role: User["role"], overrides: Partial<User> = {}): User {
  return {
    id: `user-${role.toLowerCase()}`,
    clinicId: CLINIC.id,
    email: `${role.toLowerCase()}@city.test`,
    fullName: role === "DOCTOR" ? "Dr. Sharma" : "Desk Person",
    role,
    doctorProfileId: role === "DOCTOR" ? SHARMA.id : null,
    enabled: true,
    ...overrides,
  };
}

export function session(role: User["role"]): Session {
  return { token: `token-${role}`, expiresAt: "2999-01-01T00:00:00Z", user: user(role) };
}

export function row(overrides: Partial<BoardEntry> & { id: string; tokenNumber: number }): BoardEntry {
  return {
    appointmentId: `appt-${overrides.id}`,
    status: "WAITING",
    patientName: `Patient ${overrides.tokenNumber}`,
    patientsAhead: null,
    checkedInAt: null,
    calledAt: null,
    version: 0,
    statusCode: `code-${overrides.id}`,
    ...overrides,
  };
}

export function board(doctorId: string, entries: BoardEntry[], queueDate = CLINIC.today): QueueBoard {
  return { doctorId, queueDate, currentToken: null, waitingCount: 0, entries };
}

let eventCounter = 0;
export function event(overrides: Partial<QueueEvent> & { queueEntryId: string; entryVersion: number }): QueueEvent {
  eventCounter += 1;
  return {
    eventId: `event-${eventCounter}`,
    sequence: eventCounter,
    type: "PATIENT_CALLED",
    occurredAt: "2026-03-10T05:30:00Z",
    clinicId: CLINIC.id,
    doctorId: SHARMA.id,
    queueDate: CLINIC.today,
    appointmentId: `appt-${overrides.queueEntryId}`,
    tokenNumber: 1,
    status: "CALLED",
    previousStatus: "WAITING",
    ...overrides,
  };
}

export function appointment(overrides: Partial<Appointment> & { id: string }): Appointment {
  return {
    clinicId: CLINIC.id,
    patientId: `patient-${overrides.id}`,
    patientName: `Patient ${overrides.id}`,
    doctorId: SHARMA.id,
    scheduledAt: `${CLINIC.today}T16:00:00`,
    status: "BOOKED",
    reasonSummary: null,
    ...overrides,
  };
}
