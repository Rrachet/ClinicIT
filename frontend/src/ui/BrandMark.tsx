/** The ClinicIT mark: a medical cross with a queue token at its centre (docs/brand). */
export function BrandMark({ size = 22 }: { size?: number }) {
  return (
    <svg width={size} height={size} viewBox="0 0 64 64" aria-hidden="true" focusable="false" className="brand-mark">
      <defs>
        <linearGradient id="clinicit-mark" x1="0" y1="0" x2="1" y2="1">
          <stop offset="0" stopColor="#1583e8" />
          <stop offset="1" stopColor="#0a4f9c" />
        </linearGradient>
      </defs>
      <rect width="64" height="64" rx="15" fill="url(#clinicit-mark)" />
      <rect x="25" y="11" width="14" height="42" rx="4" fill="#fff" />
      <rect x="11" y="25" width="42" height="14" rx="4" fill="#fff" />
      <circle cx="32" cy="32" r="5" fill="#1d9a58" />
    </svg>
  );
}
