"use client";

import { AccountScreen } from "@/auth/AccountScreen";
import { RequireRole } from "@/auth/RequireRole";

export default function AccountPage() {
  return (
    <RequireRole area="account">
      <AccountScreen />
    </RequireRole>
  );
}
