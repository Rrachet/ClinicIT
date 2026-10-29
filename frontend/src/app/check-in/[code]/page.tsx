import type { Metadata } from "next";
import { CheckInScreen } from "@/checkin/CheckInScreen";

export const metadata: Metadata = { title: "Check in · ClinicIT" };

export default async function CheckInPage({ params }: { params: Promise<{ code: string }> }) {
  const { code } = await params;
  return <CheckInScreen code={code} />;
}
