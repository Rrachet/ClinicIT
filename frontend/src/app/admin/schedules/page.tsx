"use client";

import { ScheduleEditor } from "@/admin/ScheduleEditor";
import { RequireRole } from "@/auth/RequireRole";

export default function SchedulesPage() {
  return (
    <RequireRole area="admin">
      <ScheduleEditor />
    </RequireRole>
  );
}
