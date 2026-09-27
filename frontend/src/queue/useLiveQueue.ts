"use client";

import { useCallback, useEffect, useRef, useState } from "react";
import { ApiError } from "@/api/client";
import { WS_URL } from "@/api/config";
import { useAuth } from "@/auth/AuthProvider";
import { openQueueFeed, type FeedStatus, type QueueFeedOptions } from "@/realtime/queueFeed";
import { applyBoard, applyEvent, emptyQueue, type EventOutcome, type QueueState } from "./queueStore";

export interface LiveQueueOptions {
  /** Doctors whose boards make up this view. */
  doctorIds: string[];
  /** The topic to follow (clinic-wide for reception, own doctor topic for doctors). */
  destination: string | null;
  /** Called after any real change, e.g. to refresh the appointment list. */
  onChange?: () => void;
  /** Test seam for the STOMP client. */
  createClient?: QueueFeedOptions["createClient"];
}

/**
 * Today's queue, kept live: REST boards + WebSocket events, reconciled by queueStore.
 * Reloads a doctor's board when an event mentions an entry we have never seen (to get
 * the patient name, which events never carry) and reloads everything after (re)connecting,
 * because the backend does not replay missed events.
 */
export function useLiveQueue({ doctorIds, destination, onChange, createClient }: LiveQueueOptions) {
  const { api, session, endSession } = useAuth();
  const [queue, setQueue] = useState<QueueState>(() => emptyQueue());
  const queueRef = useRef(queue);
  const [loaded, setLoaded] = useState(false);
  const [error, setError] = useState<ApiError | null>(null);
  const [feedStatus, setFeedStatus] = useState<FeedStatus>("connecting");
  const onChangeRef = useRef(onChange);

  useEffect(() => {
    onChangeRef.current = onChange;
  }, [onChange]);

  const commit = useCallback((next: QueueState) => {
    queueRef.current = next;
    setQueue(next);
  }, []);

  const doctorKey = doctorIds.join(",");

  const reloadDoctor = useCallback(
    (doctorId: string) => api.board(doctorId).then((board) => commit(applyBoard(queueRef.current, board))),
    [api, commit],
  );

  const reload = useCallback(
    () =>
      Promise.all(doctorKey ? doctorKey.split(",").map(reloadDoctor) : []).then(
        () => {
          setError(null);
          setLoaded(true);
        },
        (e: unknown) => setError(e instanceof ApiError ? e : new ApiError(0, "UNKNOWN", String(e))),
      ),
    [doctorKey, reloadDoctor],
  );

  useEffect(() => {
    void reload();
  }, [reload]);

  const token = session?.token;
  useEffect(() => {
    if (!destination || !token) return;
    const feed = openQueueFeed({
      url: WS_URL,
      token,
      destination,
      createClient,
      onStatus: setFeedStatus,
      onAuthError: () => endSession("expired"),
      onConnected: () => void reload(),
      onEvent: (event) => {
        const { state, outcome } = applyEvent(queueRef.current, event);
        commit(state);
        if (reactsTo(outcome)) {
          if (outcome === "unknown-entry") void reloadDoctor(event.doctorId).catch(() => undefined);
          onChangeRef.current?.();
        }
      },
    });
    return () => feed.close();
  }, [destination, token, createClient, endSession, reload, reloadDoctor, commit]);

  return { queue, loaded, error, feedStatus, reload };
}

function reactsTo(outcome: EventOutcome) {
  return outcome === "applied" || outcome === "unknown-entry";
}
