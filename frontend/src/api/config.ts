/** Backend location, inlined at build time (NEXT_PUBLIC_API_BASE_URL). */
export const API_BASE_URL = (process.env.NEXT_PUBLIC_API_BASE_URL ?? "http://localhost:8080").replace(/\/$/, "");

/** STOMP endpoint on the same host: http→ws, https→wss. */
export const WS_URL = `${API_BASE_URL.replace(/^http/, "ws")}/ws`;
