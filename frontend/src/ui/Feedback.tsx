import { ApiError } from "@/api/client";

/** Human wording for API failures; the server's message is shown for business rules. */
export function describeError(error: unknown): string {
  if (error instanceof ApiError) {
    if (error.status === 0) return error.message;
    if (error.isForbidden) return "You don't have permission to do that.";
    if (error.status === 404) return "That record no longer exists or isn't in your clinic.";
    if (error.status >= 500) return "Something went wrong on the server. Please try again.";
    return error.message;
  }
  return "Something went wrong. Please try again.";
}

export function ErrorBanner({ error, onDismiss, onRetry }: { error: unknown; onDismiss?: () => void; onRetry?: () => void }) {
  if (!error) return null;
  return (
    <div className="banner banner-error" role="alert">
      <span>{describeError(error)}</span>
      <span className="banner-actions">
        {onRetry ? (
          <button type="button" className="link-button" onClick={onRetry}>
            Retry
          </button>
        ) : null}
        {onDismiss ? (
          <button type="button" className="link-button" onClick={onDismiss} aria-label="Dismiss">
            ✕
          </button>
        ) : null}
      </span>
    </div>
  );
}

export function Notice({ children }: { children: React.ReactNode }) {
  return (
    <div className="banner banner-info" role="status">
      {children}
    </div>
  );
}

export function EmptyState({ title, hint }: { title: string; hint?: string }) {
  return (
    <div className="empty">
      <p className="empty-title">{title}</p>
      {hint ? <p className="empty-hint">{hint}</p> : null}
    </div>
  );
}
