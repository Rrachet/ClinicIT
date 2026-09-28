import { describe, expect, it, vi } from "vitest";
import { ApiError, createApiClient } from "./client";

function jsonResponse(status: number, body?: unknown) {
  return new Response(body === undefined ? null : JSON.stringify(body), { status, headers: { "Content-Type": "application/json" } });
}

describe("createApiClient", () => {
  it("attaches the bearer token and JSON body", async () => {
    const fetchImpl = vi.fn(async () => jsonResponse(200, { ok: true }));
    const client = createApiClient({ baseUrl: "http://api.test/", getToken: () => "abc", fetchImpl });

    await client.post("/api/v1/patients", { body: { fullName: "A" }, query: { skip: undefined, name: "x" } });

    const [url, init] = fetchImpl.mock.calls[0] as unknown as [string, RequestInit];
    expect(url).toBe("http://api.test/api/v1/patients?name=x");
    expect(init.headers).toMatchObject({ Authorization: "Bearer abc", "Content-Type": "application/json" });
    expect(init.body).toBe(JSON.stringify({ fullName: "A" }));
  });

  it("refuses to send anything when no API is configured, instead of calling this site", async () => {
    const fetchImpl = vi.fn(async () => jsonResponse(200, {}));
    const client = createApiClient({ baseUrl: "", getToken: () => "abc", fetchImpl });

    const error = await client.get("/api/v1/clinic").catch((e: unknown) => e);

    expect(error).toBeInstanceOf(ApiError);
    expect(error).toMatchObject({ status: 0, code: "API_NOT_CONFIGURED" });
    expect(fetchImpl).not.toHaveBeenCalled();
  });

  it("sends no token for anonymous requests or when signed out", async () => {
    const fetchImpl = vi.fn(async () => jsonResponse(200, {}));
    await createApiClient({ baseUrl: "http://api.test", getToken: () => "abc", fetchImpl }).get("/x", { anonymous: true });
    await createApiClient({ baseUrl: "http://api.test", getToken: () => null, fetchImpl }).get("/y");
    for (const call of fetchImpl.mock.calls as unknown as [string, RequestInit][]) {
      expect(call[1].headers).not.toHaveProperty("Authorization");
    }
  });

  it("turns the backend's ApiError body into an ApiError", async () => {
    const fetchImpl = vi.fn(async () => jsonResponse(409, { status: 409, code: "DOCTOR_BUSY", message: "Doctor already has a patient" }));
    const client = createApiClient({ baseUrl: "http://api.test", getToken: () => "abc", fetchImpl });

    const error = await client.post("/api/v1/queues/call-next").catch((e) => e);
    expect(error).toBeInstanceOf(ApiError);
    expect(error).toMatchObject({ status: 409, code: "DOCTOR_BUSY", message: "Doctor already has a patient" });
  });

  it("reports a 401 on an authenticated request as a lost session", async () => {
    const onUnauthorized = vi.fn();
    const fetchImpl = vi.fn(async () => jsonResponse(401, { status: 401, code: "UNAUTHORIZED", message: "Authentication required" }));
    const client = createApiClient({ baseUrl: "http://api.test", getToken: () => "expired", onUnauthorized, fetchImpl });

    await expect(client.get("/api/v1/doctors")).rejects.toMatchObject({ isUnauthorized: true });
    expect(onUnauthorized).toHaveBeenCalledOnce();
  });

  it("does not treat a failed login (no token) as a lost session", async () => {
    const onUnauthorized = vi.fn();
    const fetchImpl = vi.fn(async () => jsonResponse(401, { status: 401, code: "INVALID_CREDENTIALS", message: "Invalid" }));
    const client = createApiClient({ baseUrl: "http://api.test", getToken: () => null, onUnauthorized, fetchImpl });

    await expect(client.post("/api/v1/auth/login", { anonymous: true })).rejects.toMatchObject({ code: "INVALID_CREDENTIALS" });
    expect(onUnauthorized).not.toHaveBeenCalled();
  });

  it("reports network failures and non-JSON errors", async () => {
    const offline = createApiClient({ baseUrl: "http://api.test", getToken: () => null, fetchImpl: vi.fn(async () => Promise.reject(new TypeError("fetch failed"))) });
    await expect(offline.get("/x")).rejects.toMatchObject({ status: 0, code: "NETWORK_ERROR" });

    const proxyError = createApiClient({
      baseUrl: "http://api.test",
      getToken: () => null,
      fetchImpl: vi.fn(async () => new Response("<html>Bad gateway</html>", { status: 502, statusText: "Bad Gateway" })),
    });
    await expect(proxyError.get("/x")).rejects.toMatchObject({ status: 502, code: "HTTP_502" });
  });

  it("returns undefined for 204", async () => {
    const client = createApiClient({ baseUrl: "http://api.test", getToken: () => "t", fetchImpl: vi.fn(async () => new Response(null, { status: 204 })) });
    await expect(client.post("/api/v1/auth/logout")).resolves.toBeUndefined();
  });
});
