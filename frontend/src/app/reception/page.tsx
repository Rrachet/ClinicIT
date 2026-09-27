"use client";

import { RequireRole } from "@/auth/RequireRole";
import { ReceptionConsole } from "@/reception/ReceptionConsole";

export default function ReceptionPage() {
  return (
    <RequireRole area="reception">
      <ReceptionConsole />
    </RequireRole>
  );
}
