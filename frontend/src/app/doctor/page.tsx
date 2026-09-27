"use client";

import { RequireRole } from "@/auth/RequireRole";
import { DoctorConsole } from "@/doctor/DoctorConsole";

export default function DoctorPage() {
  return (
    <RequireRole area="doctor">
      <DoctorConsole />
    </RequireRole>
  );
}
