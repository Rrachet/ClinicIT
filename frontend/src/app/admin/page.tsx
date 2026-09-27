"use client";

import { AnalyticsDashboard } from "@/admin/AnalyticsDashboard";
import { RequireRole } from "@/auth/RequireRole";

export default function AdminPage() {
  return (
    <RequireRole area="admin">
      <AnalyticsDashboard />
    </RequireRole>
  );
}
