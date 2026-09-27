"use client";

import type { Doctor } from "@/api/types";
import { doctorView, type QueueState } from "@/queue/queueStore";
import { Button } from "@/ui/Button";
import { statusLabel } from "@/ui/format";

/** One card per doctor: who is in, who is next, and the call-next button. */
export function NowServing({
  doctors,
  queue,
  busyDoctorId,
  onCallNext,
}: {
  doctors: Doctor[];
  queue: QueueState;
  busyDoctorId: string | null;
  onCallNext: (doctor: Doctor) => void;
}) {
  return (
    <section className="now-serving" aria-label="Now serving">
      {doctors.map((doctor) => {
        const view = doctorView(queue, doctor.id);
        return (
          <article key={doctor.id} className="serving-card" aria-label={`Queue for ${doctor.displayName}`}>
            <header className="serving-head">
              <h2>{doctor.displayName}</h2>
              <span className="muted">{view.waiting.length} waiting</span>
            </header>
            <div className="serving-body">
              <div className="serving-current">
                <span className="label">Now serving</span>
                <span className="token token-xl" data-testid={`current-token-${doctor.id}`}>
                  {view.current ? `#${view.current.tokenNumber}` : "—"}
                </span>
                <span className="serving-name">
                  {view.current ? `${view.current.patientName ?? "…"} · ${statusLabel(view.current.status)}` : "No one called"}
                </span>
              </div>
              <div className="serving-next">
                <span className="label">Next</span>
                {view.waiting.length === 0 ? (
                  <span className="muted">Queue empty</span>
                ) : (
                  <ol className="next-list">
                    {view.waiting.slice(0, 3).map((item) => (
                      <li key={item.id}>
                        <span className="token token-sm">#{item.tokenNumber}</span> {item.patientName ?? "…"}
                      </li>
                    ))}
                  </ol>
                )}
              </div>
            </div>
            <Button
              variant="primary"
              size="lg"
              className="serving-call"
              busy={busyDoctorId === doctor.id}
              disabled={view.waiting.length === 0 || view.current !== null}
              title={view.current ? "Finish or skip the current patient first" : undefined}
              onClick={() => onCallNext(doctor)}
            >
              {view.waiting.length ? `Call #${view.waiting[0].tokenNumber}` : "Call next"}
            </Button>
          </article>
        );
      })}
    </section>
  );
}
