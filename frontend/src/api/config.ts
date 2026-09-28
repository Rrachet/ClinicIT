/**
 * Backend location, inlined at build time (NEXT_PUBLIC_API_BASE_URL). Only development falls
 * back to a local API: a production build without the variable has no API at all, and the
 * app says so instead of sending every visitor's browser to localhost.
 */
const configured = (process.env.NEXT_PUBLIC_API_BASE_URL ?? "").trim().replace(/\/$/, "");
export const API_BASE_URL = configured || (process.env.NODE_ENV === "production" ? "" : "http://localhost:8080");

/** False on a production build made without NEXT_PUBLIC_API_BASE_URL. */
export const API_CONFIGURED = API_BASE_URL !== "";

/** STOMP endpoint on the same host: http→ws, https→wss. */
export const WS_URL = API_CONFIGURED ? `${API_BASE_URL.replace(/^http/, "ws")}/ws` : "";
