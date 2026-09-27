export function Spinner({ label = "Loading" }: { label?: string }) {
  return (
    <span className="spinner-wrap" role="status">
      <span className="spinner" aria-hidden />
      <span className="sr-only">{label}</span>
    </span>
  );
}

export function FullPageSpinner({ label }: { label: string }) {
  return (
    <div className="full-page">
      <Spinner label={label} />
    </div>
  );
}
