import type { Metadata } from "next";
import { ReceptionistChat } from "@/receptionist/ReceptionistChat";

export const metadata: Metadata = { title: "Talk to reception · ClinicIT" };

export default async function TalkPage({ params }: { params: Promise<{ code: string }> }) {
  const { code } = await params;
  return <ReceptionistChat code={code} />;
}
