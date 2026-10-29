/** Sound and vibration for "it's your turn". Browsers allow both only after a tap, so the
 * patient turns them on with a button; nothing plays without that. */
export interface Alerter {
  /** A short confirmation that alerts work. */
  test(): void;
  /** The "it's your turn" alert. */
  alert(): void;
}

export function createAlerter(): Alerter {
  const AudioCtx: typeof AudioContext | undefined =
    typeof window === "undefined"
      ? undefined
      : (window.AudioContext ?? (window as unknown as { webkitAudioContext?: typeof AudioContext }).webkitAudioContext);
  let context: AudioContext | null = null;
  try {
    context = AudioCtx ? new AudioCtx() : null;
  } catch {
    context = null;
  }

  const beep = (at: number, ms: number) => {
    if (!context) return;
    const oscillator = context.createOscillator();
    const gain = context.createGain();
    oscillator.frequency.value = 880;
    gain.gain.value = 0.25;
    oscillator.connect(gain).connect(context.destination);
    const start = context.currentTime + at / 1000;
    oscillator.start(start);
    oscillator.stop(start + ms / 1000);
  };
  const vibrate = (pattern: number[]) => {
    try {
      navigator.vibrate?.(pattern);
    } catch {
      // Not supported (e.g. iPhone): the screen still changes.
    }
  };

  return {
    test() {
      void context?.resume();
      beep(0, 120);
      vibrate([80]);
    },
    alert() {
      void context?.resume();
      [0, 400, 800].forEach((at) => beep(at, 250));
      vibrate([300, 150, 300, 150, 300]);
    },
  };
}
