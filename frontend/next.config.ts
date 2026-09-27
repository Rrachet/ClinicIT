import type { NextConfig } from "next";

const apiBase = process.env.NEXT_PUBLIC_API_BASE_URL ?? "http://localhost:8080";
const wsBase = apiBase.replace(/^http/, "ws");

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
            value: `connect-src 'self' ${apiBase} ${wsBase}; frame-ancestors 'none'; object-src 'none'; base-uri 'self'`,
          },
        ],
      },
    ];
  },
};

export default nextConfig;
