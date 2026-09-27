"use client";

import { useEffect, useState } from "react";
import type { ClinicApi } from "@/api/clinicApi";
import type { WaitEstimate } from "@/api/types";

const DEBOUNCE_MS = 1_500;
const REFRESH_MS = 60_000;

/**
 * Estimated waits for the given doctors' waiting patients, by queue entry id.
 *
 * Refetched after a doctor's queue signature changes (debounced, so a burst of live events
 * costs one request) and once a minute while the queue is quiet (the patient in the room
 * keeps using up time). The server caches too, so the ML service sees at most one request
 * per queue change. Estimates are advisory: on any error the screen simply shows none.
 */
export function useWaitEstimates(
  api: ClinicApi,
  signatures: Record<string, string>,
  { debounceMs = DEBOUNCE_MS, refreshMs = REFRESH_MS }: { debounceMs?: number; refreshMs?: number } = {},
): Record<string, WaitEstimate> {
  const [byEntry, setByEntry] = useState<Record<string, WaitEstimate>>({});
  const key = Object.keys(signatures)
    .sort()
    .map((doctorId) => `${doctorId}=${signatures[doctorId]}`)
    .join(";");

  useEffect(() => {
    const doctorIds = key ? key.split(";").map((part) => part.split("=")[0]) : [];
    if (doctorIds.length === 0) return;
    let active = true;
    const load = () =>
      Promise.all(doctorIds.map((id) => api.waitEstimates(id).catch(() => null))).then((results) => {
        if (!active) return;
        const next: Record<string, WaitEstimate> = {};
        for (const result of results) {
          for (const entry of result?.entries ?? []) next[entry.queueEntryId] = entry;
        }
        setByEntry(next);
      });
    const debounce = setTimeout(load, debounceMs);
    const refresh = setInterval(load, refreshMs);
    return () => {
      active = false;
      clearTimeout(debounce);
      clearInterval(refresh);
    };
  }, [api, key, debounceMs, refreshMs]);

  return byEntry;
}
