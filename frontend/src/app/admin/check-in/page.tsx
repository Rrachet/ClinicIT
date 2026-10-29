"use client";

import { CheckInAdmin } from "@/admin/CheckInAdmin";
import { RequireRole } from "@/auth/RequireRole";

export default function CheckInAdminPage() {
  return (
    <RequireRole area="admin">
      <CheckInAdmin />
    </RequireRole>
  );
}
