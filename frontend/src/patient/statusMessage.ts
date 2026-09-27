import type { PublicQueueStatus } from "@/api/types";

export interface StatusMessage {
  tone: "wait" | "next" | "go" | "done" | "attention";
  headline: string;
  detail: string;
}

/** Plain-language wording for the patient's screen. Only phrases what the server returned. */
export function statusMessage(status: PublicQueueStatus): StatusMessage {
  switch (status.status) {
    case "CALLED":
      return { tone: "go", headline: "It's your turn", detail: `Please go to ${status.doctorName ?? "the doctor"} now.` };
    case "IN_CONSULTATION":
      return { tone: "go", headline: "You're with the doctor", detail: "" };
    case "COMPLETED":
      return { tone: "done", headline: "Consultation complete", detail: "Thank you for visiting." };
    case "SKIPPED":
      return { tone: "attention", headline: "You missed your call", detail: "Please speak to reception to rejoin the queue." };
    case "NO_SHOW":
      return { tone: "attention", headline: "Marked as not attending", detail: "Please speak to reception if you are here." };
    case "WAITING":
      return status.patientsAhead === 0
        ? { tone: "next", headline: "You're next", detail: "Please stay close — you will be called shortly." }
        : {
            tone: "wait",
            headline: `${status.patientsAhead} ${status.patientsAhead === 1 ? "patient" : "patients"} ahead of you`,
            detail: "We'll update this page automatically.",
          };
  }
}
