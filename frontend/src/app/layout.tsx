import type { Metadata, Viewport } from "next";
import { AuthProvider } from "@/auth/AuthProvider";
import "./globals.css";

const description =
  "Self check-in and live queues for outpatient clinics: patients scan a QR code, are matched to a doctor who is ready, and follow their place on their own phone.";

export const metadata: Metadata = {
  metadataBase: new URL(process.env.NEXT_PUBLIC_SITE_URL ?? "https://clinicit.vercel.app"),
  title: { default: "ClinicIT · Run the clinic day without a reception desk", template: "%s" },
  description,
  applicationName: "ClinicIT",
  openGraph: {
    type: "website",
    siteName: "ClinicIT",
    title: "ClinicIT · Run the clinic day without a reception desk",
    description,
  },
  twitter: { card: "summary_large_image", title: "ClinicIT", description },
  // Staff and patient pages are not for search engines.
  robots: { index: false, follow: false },
};

export const viewport: Viewport = {
  width: "device-width",
  initialScale: 1,
};

export default function RootLayout({ children }: Readonly<{ children: React.ReactNode }>) {
  return (
    <html lang="en">
      <body>
        <AuthProvider>{children}</AuthProvider>
      </body>
    </html>
  );
}
