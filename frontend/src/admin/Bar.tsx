/** A horizontal bar with its value; decorative, the label carries the number. */
export function Bar({ percent: width, label, tone }: { percent: number; label: string; tone?: "muted" | "warn" }) {
  return (
    <span className="bar-cell">
      <span className="bar" aria-hidden>
        <span className={`bar-fill${tone ? ` bar-${tone}` : ""}`} style={{ width: `${width}%` }} />
      </span>
      {label}
    </span>
  );
}
