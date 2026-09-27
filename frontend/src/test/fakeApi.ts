import { vi } from "vitest";

type Handler = (request: { method: string; path: string; body: unknown; headers: Record<string, string> }) =>
  | { status?: number; body?: unknown }
  | undefined;

export interface RecordedCall {
  method: string;
  path: string;
  body: unknown;
  authorization: string | undefined;
}

/**
 * A routing fetch stub: `route("GET /api/v1/clinic", () => ({ body: CLINIC }))`.
 * Unrouted requests fail the test loudly. Every call is recorded.
 */
export function fakeApi() {
  const routes = new Map<string, Handler>();
  const calls: RecordedCall[] = [];

  const fetchMock = vi.fn(async (input: RequestInfo | URL, init?: RequestInit) => {
    const url = new URL(String(input));
    const method = init?.method ?? "GET";
    const headers = (init?.headers ?? {}) as Record<string, string>;
    const body = init?.body ? JSON.parse(String(init.body)) : undefined;
    calls.push({ method, path: url.pathname + url.search, body, authorization: headers.Authorization });

    const handler = routes.get(`${method} ${url.pathname}`);
    if (!handler) throw new Error(`Unexpected request: ${method} ${url.pathname}`);
    const result = handler({ method, path: url.pathname, body, headers }) ?? {};
    const status = result.status ?? 200;
    return new Response(result.body === undefined ? null : JSON.stringify(result.body), {
      status,
      headers: { "Content-Type": "application/json" },
    });
  });

  return {
    fetchMock,
    calls,
    route(key: string, handler: Handler) {
      routes.set(key, handler);
      return this;
    },
    callsTo(method: string, path: string) {
      return calls.filter((c) => c.method === method && c.path.split("?")[0] === path);
    },
  };
}

export function apiError(status: number, code: string, message: string) {
  return { status, body: { status, code, message, path: "/test" } };
}
