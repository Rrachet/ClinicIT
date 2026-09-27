"use client";

import { useEffect, useRef } from "react";
import { Button } from "./Button";

export interface ConfirmRequest {
  title: string;
  message: string;
  confirmLabel: string;
  danger?: boolean;
}

/** Modal confirmation for destructive actions. Escape or Cancel closes it. */
export function ConfirmDialog({
  request,
  busy,
  onConfirm,
  onCancel,
}: {
  request: ConfirmRequest | null;
  busy?: boolean;
  onConfirm: () => void;
  onCancel: () => void;
}) {
  const cancelRef = useRef<HTMLButtonElement>(null);

  useEffect(() => {
    if (!request) return;
    cancelRef.current?.focus();
    const onKey = (e: KeyboardEvent) => e.key === "Escape" && onCancel();
    window.addEventListener("keydown", onKey);
    return () => window.removeEventListener("keydown", onKey);
  }, [request, onCancel]);

  if (!request) return null;
  return (
    <div className="overlay">
      <div className="dialog" role="alertdialog" aria-modal="true" aria-labelledby="confirm-title" aria-describedby="confirm-message">
        <h2 id="confirm-title">{request.title}</h2>
        <p id="confirm-message">{request.message}</p>
        <div className="dialog-actions">
          <Button ref={cancelRef} onClick={onCancel} disabled={busy}>
            Keep it
          </Button>
          <Button variant={request.danger ? "danger" : "primary"} onClick={onConfirm} busy={busy}>
            {request.confirmLabel}
          </Button>
        </div>
      </div>
    </div>
  );
}
