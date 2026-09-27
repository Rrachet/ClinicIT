"use client";

import { useCallback, useEffect, useState } from "react";

export interface AsyncState<T> {
  data: T | undefined;
  error: unknown;
  loading: boolean;
  reload: () => void;
}

/**
 * Loads data for a screen. `load` must be memoised (useCallback); pass null to wait.
 * Keeps showing the previous data while reloading, so live screens don't flicker.
 */
export function useAsync<T>(load: (() => Promise<T>) | null): AsyncState<T> {
  const [result, setResult] = useState<{ data?: T; error?: unknown; settled: boolean }>({ settled: false });
  const [version, setVersion] = useState(0);

  useEffect(() => {
    if (!load) return;
    let active = true;
    load().then(
      (data) => active && setResult({ data, settled: true }),
      (error) => active && setResult((previous) => ({ ...previous, error, settled: true })),
    );
    return () => {
      active = false;
    };
  }, [load, version]);

  const reload = useCallback(() => setVersion((v) => v + 1), []);
  return { data: result.data, error: result.error, loading: !result.settled, reload };
}
