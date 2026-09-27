"use client";

import { useState } from "react";
import { Button } from "@/ui/Button";
import { statusLink } from "./workflows";

/** Shows the patient's status link so it can be copied into an SMS/WhatsApp by hand. */
export function StatusLinkDialog({ link, onClose }: { link: { code: string; token: number } | null; onClose: () => void }) {
  const [copied, setCopied] = useState(false);
  if (!link) return null;
  const url = statusLink(link.code);
  return (
    <div className="overlay">
      <div className="dialog" role="dialog" aria-modal="true" aria-labelledby="link-title">
        <h2 id="link-title">Queue link for token #{link.token}</h2>
        <p className="muted">
          Give this link only to the patient. It shows their place in the queue and nothing else, and stops working
          after today.
        </p>
        <input className="link-box" value={url} readOnly aria-label="Patient status link" onFocus={(e) => e.target.select()} />
        <div className="dialog-actions">
          <Button
            onClick={async () => {
              await navigator.clipboard?.writeText(url);
              setCopied(true);
            }}
          >
            {copied ? "Copied" : "Copy link"}
          </Button>
          <Button variant="primary" onClick={onClose} autoFocus>
            Done
          </Button>
        </div>
      </div>
    </div>
  );
}
