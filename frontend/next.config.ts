import type { NextConfig } from "next";

// Same rule as src/api/config.ts: only development falls back to a local API.
const apiBase =
  (process.env.NEXT_PUBLIC_API_BASE_URL ?? "").trim().replace(/\/$/, "") ||
  (process.env.NODE_ENV === "production" ? "" : "http://localhost:8080");
const wsBase = apiBase.replace(/^http/, "ws");

if (process.env.VERCEL_ENV === "production") {
  // A browser on an https page cannot call a plain-http API; fail the build rather than ship that.
  if (apiBase.startsWith("http://")) throw new Error("NEXT_PUBLIC_API_BASE_URL must be an https:// URL in production.");
  if (!apiBase) console.warn("NEXT_PUBLIC_API_BASE_URL is not set: this build cannot reach a ClinicIT API.");
}

const nextConfig: NextConfig = {
  poweredByHeader: false,
  async headers() {
    return [
      {
        source: "/:path*",
        headers: [
          // Patient status links carry a code in the URL: never leak it via Referer.
          { key: "Referrer-Policy", value: "no-referrer" },
          { key: "X-Content-Type-Options", value: "nosniff" },
          { key: "X-Frame-Options", value: "DENY" },
          { key: "Permissions-Policy", value: "camera=(), microphone=(), geolocation=()" },
          {
            // The browser may only talk to this app and the ClinicIT API, which limits
            // where an injected script could send a token.
            key: "Content-Security-Policy",
            value: `connect-src 'self'${apiBase ? ` ${apiBase} ${wsBase}` : ""}; frame-ancestors 'none'; object-src 'none'; base-uri 'self'`,
          },
        ],
      },
    ];
  },
};

export default nextConfig;
