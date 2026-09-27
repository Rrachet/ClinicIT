import type { ApiErrorBody } from "./types";

/** An error response from the API, normalised to the backend's ApiError shape. */
export class ApiError extends Error {
  constructor(
    readonly status: number,
    readonly code: string,
    message: string,
  ) {
    super(message);
    this.name = "ApiError";
  }

  get isUnauthorized() {
    return this.status === 401;
  }

  get isForbidden() {
    return this.status === 403;
  }
}

export interface ApiClientOptions {
  baseUrl: string;
  /** Current bearer token, or null when signed out. */
  getToken: () => string | null;
  /** Called when an authenticated request gets 401: the session is gone. */
  onUnauthorized?: () => void;
  fetchImpl?: typeof fetch;
}

export interface RequestOptions {
  body?: unknown;
  query?: Record<string, string | undefined>;
  /** Send without the bearer token (login, public status). */
  anonymous?: boolean;
}

export type ApiClient = ReturnType<typeof createApiClient>;

/**
 * The one place that talks HTTP to the backend: attaches the bearer token, sends JSON,
 * and turns every failure (HTTP or network) into an ApiError.
 */
export function createApiClient(options: ApiClientOptions) {
  const doFetch = options.fetchImpl ?? ((...args: Parameters<typeof fetch>) => fetch(...args));
  const base = options.baseUrl.replace(/\/$/, "");

  async function request<T>(method: string, path: string, opts: RequestOptions = {}): Promise<T> {
    const url = new URL(base + path);
    for (const [key, value] of Object.entries(opts.query ?? {})) {
      if (value !== undefined && value !== "") url.searchParams.set(key, value);
    }

    const headers: Record<string, string> = { Accept: "application/json" };
    if (opts.body !== undefined) headers["Content-Type"] = "application/json";
    const token = opts.anonymous ? null : options.getToken();
    if (token) headers.Authorization = `Bearer ${token}`;

    let response: Response;
    try {
      response = await doFetch(url.toString(), {
        method,
        headers,
        body: opts.body === undefined ? undefined : JSON.stringify(opts.body),
        cache: "no-store",
      });
    } catch {
      throw new ApiError(0, "NETWORK_ERROR", "Cannot reach the ClinicIT server. Check your connection.");
    }

    if (response.status === 204) return undefined as T;

    const text = await response.text();
    const data: unknown = text ? safeJson(text) : undefined;

    if (!response.ok) {
      const body = (data ?? {}) as Partial<ApiErrorBody>;
      const error = new ApiError(
        response.status,
        body.code ?? `HTTP_${response.status}`,
        body.message ?? response.statusText ?? "Request failed",
      );
      if (error.isUnauthorized && token) options.onUnauthorized?.();
      throw error;
    }
    return data as T;
  }

  return {
    get: <T>(path: string, opts?: RequestOptions) => request<T>("GET", path, opts),
    post: <T>(path: string, opts?: RequestOptions) => request<T>("POST", path, opts),
  };
}

function safeJson(text: string): unknown {
  try {
    return JSON.parse(text);
  } catch {
    return undefined;
  }
}
