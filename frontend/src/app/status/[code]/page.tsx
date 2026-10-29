import type { Metadata } from "next";
import { PatientStatus } from "@/patient/PatientStatus";

export const metadata: Metadata = { title: "Your place in the queue · ClinicIT" };

export default async function StatusPage({
  params,
  searchParams,
}: {
  params: Promise<{ code: string }>;
  searchParams: Promise<{ checkedIn?: string }>;
}) {
  const { code } = await params;
  const { checkedIn } = await searchParams;
  return <PatientStatus code={code} justCheckedIn={checkedIn === "1"} />;
}
