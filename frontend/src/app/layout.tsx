import type { Metadata, Viewport } from "next";
import { AuthProvider } from "@/auth/AuthProvider";
import "./globals.css";

export const metadata: Metadata = {
  title: "ClinicIT",
  description: "Clinic operations: appointments, live queue and patient status",
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
