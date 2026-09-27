import type { Role } from "@/api/types";

/**
 * Which screen each role lands on. This is navigation only: the backend decides what
 * every request may do, so hiding a screen here is never relied on for security.
 */
export type Area = "reception" | "doctor";

const AREA_ROLES: Record<Area, readonly Role[]> = {
  // Admins have front-desk rights on the backend, so they use the reception console.
  reception: ["ADMIN", "RECEPTIONIST"],
  doctor: ["DOCTOR"],
};

export function homeFor(role: Role): string {
  return role === "DOCTOR" ? "/doctor" : "/reception";
}

export function canEnter(role: Role, area: Area): boolean {
  return AREA_ROLES[area].includes(role);
}
