import type { Metadata } from "next";
import { PatientStatus } from "@/patient/PatientStatus";

export const metadata: Metadata = { title: "Your place in the queue · ClinicIT" };

export default async function StatusPage({ params }: { params: Promise<{ code: string }> }) {
  const { code } = await params;
  return <PatientStatus code={code} />;
}
