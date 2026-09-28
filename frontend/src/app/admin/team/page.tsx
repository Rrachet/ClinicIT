"use client";

import { TeamScreen } from "@/admin/TeamScreen";
import { RequireRole } from "@/auth/RequireRole";

export default function TeamPage() {
  return (
    <RequireRole area="admin">
      <TeamScreen />
    </RequireRole>
  );
}
