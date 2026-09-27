import { vi } from "vitest";

/** Shared mock for next/navigation; import in a test file with vi.mock("next/navigation", ...). */
export const router = { replace: vi.fn(), push: vi.fn(), refresh: vi.fn(), back: vi.fn(), forward: vi.fn(), prefetch: vi.fn() };
export const searchParams = { value: new URLSearchParams() };

export const navigationMock = {
  useRouter: () => router,
  useSearchParams: () => searchParams.value,
  usePathname: () => "/",
};
