import { Suspense } from "react";
import { LoginScreen } from "@/auth/LoginScreen";

export default function LoginPage() {
  // LoginScreen reads ?reason=expired with useSearchParams, which needs a Suspense boundary.
  return (
    <Suspense>
      <LoginScreen />
    </Suspense>
  );
}
