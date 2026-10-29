import Link from "next/link";
import { HomeActions } from "./HomeActions";
import { BrandMark } from "@/ui/BrandMark";

const PATIENT_STEPS = [
  {
    title: "Doctor goes live",
    who: "Doctor",
    text: "The doctor signs in, sets their room and taps “Go live”. From then on, patients can be sent to them.",
  },
  {
    title: "Patient scans the QR code",
    who: "Patient",
    text: "A poster in the waiting area opens the clinic's check-in page on the patient's phone. No app, no account.",
  },
  {
    title: "Name, number, visit type",
    who: "Patient",
    text: "They enter their name and phone number, choose what they are here for (say, Child or Skin), and can add a note for the doctor.",
  },
  {
    title: "Matched and queued",
    who: "ClinicIT",
    text: "ClinicIT picks a live doctor who sees that visit type, the one with the shortest queue, and gives the patient a token.",
  },
  {
    title: "Wait with a live ETA",
    who: "Patient",
    text: "Their page shows the token being served, how many are ahead and an estimated wait, and updates by itself.",
  },
  {
    title: "“Go to Room 3”",
    who: "Doctor",
    text: "The doctor taps “Call next”. The patient's phone shows the room in large type, and can beep and vibrate.",
  },
] as const;

const ROLES = [
  {
    role: "Patients",
    points: [
      "Scan, type a name and number, pick a visit: done",
      "Their token, the token being served and a wait range",
      "“It's your turn, go to Room 3”, without refreshing",
    ],
  },
  {
    role: "Doctors",
    points: [
      "“Go live” when ready, “Go offline” when done",
      "The current patient, their note, and who is next",
      "Call next, start and complete in one click each",
    ],
  },
  {
    role: "Clinic admins",
    points: [
      "Print the check-in QR poster",
      "Set visit types and which doctors see each",
      "Watch every queue and fix anything by hand",
    ],
  },
  {
    role: "Front desk (optional)",
    points: [
      "Not needed for check-in",
      "Can still book ahead, add walk-ins without a phone",
      "Skip, requeue, no-show and cancel",
    ],
  },
] as const;

const FEATURES = [
  { title: "QR self check-in", text: "Patients check themselves in from their own phone. Repeat scans return the same place, never a second token." },
  { title: "Fair doctor matching", text: "By the visit type the patient picks and the shortest live queue. Nothing is guessed from what they wrote." },
  { title: "Live token queue", text: "Tokens are allocated atomically, one patient is with a doctor at a time, and every screen stays in sync." },
  { title: "Wait estimates", text: "An approximate range from the live queue and the clinic's own history. Always a range, never a promise." },
  { title: "Clinic figures", text: "Average and median wait, consultations, no-show rate, hourly queue and doctor workload, from recorded history." },
  { title: "Secure by default", text: "Staff sign-in with roles, every query scoped to your clinic, rate limits on public pages, nothing sensitive in logs." },
] as const;

const REQUEST_FLOW = [
  { label: "Doctor taps “Call next”", detail: "Signed-in doctor, own queue only" },
  { label: "One database transaction", detail: "Queue, appointment and history change together, or not at all" },
  { label: "Change recorded, then announced", detail: "Only committed changes are ever sent" },
  { label: "Every screen updates", detail: "Staff screens live over WebSocket; the patient's phone within seconds" },
] as const;

const DELIVERY = [
  { label: "Code change", detail: "A pull request on GitHub" },
  { label: "Automated checks", detail: "Backend against PostgreSQL, web app tests, browser end-to-end, full Docker stack" },
  { label: "Review and merge", detail: "Only green changes reach main" },
  { label: "Deploy", detail: "Vercel builds the web app and the API container; the database is Supabase PostgreSQL" },
] as const;

/** The public home page: what ClinicIT does, how a patient moves through it, and how it is built. */
export function HomePage() {
  return (
    <div className="home">
      <header className="home-nav">
        <Link href="/" className="brand home-brand">
          <BrandMark size={28} /> ClinicIT
        </Link>
        <nav aria-label="Home sections" className="home-nav-links">
          <a href="#how-it-works">How it works</a>
          <a href="#roles">For your team</a>
          <a href="#features">Features</a>
          <a href="#pipeline">Under the hood</a>
        </nav>
        <HomeActions compact />
      </header>

      <main>
        <section className="home-hero" aria-labelledby="home-title">
          <div className="home-hero-copy">
            <p className="home-eyebrow">Self check-in and live queues for outpatient clinics</p>
            <h1 id="home-title">Run the clinic day without a reception desk.</h1>
            <p className="home-lead">
              Patients scan a QR code, check themselves in and are matched to a doctor who is ready. Their phone shows
              their place, the wait and which room to go to.
            </p>
            <HomeActions />
            <p className="muted small">Patients: scan the QR code in the clinic. No app or account is needed.</p>
          </div>

          <div className="home-hero-visual" aria-hidden="true">
            <div className="home-mock home-mock-desk">
              <div className="home-mock-head">
                <span>Dr. Sharma · Room 3</span>
                <span className="home-mock-live">
                  <span className="live-dot" /> Live
                </span>
              </div>
              <div className="home-mock-body">
                <div>
                  <span className="label">Now serving</span>
                  <span className="token token-xl">#24</span>
                </div>
                <div>
                  <span className="label">Up next</span>
                  <span className="token token-md">#25</span>
                  <span className="muted small">3 waiting</span>
                </div>
              </div>
              <span className="home-mock-button">Call next</span>
            </div>
            <div className="home-mock home-mock-phone">
              <span className="label">Your token</span>
              <span className="token token-xl">#27</span>
              <strong>2 patients ahead of you</strong>
              <span className="muted small">Estimated wait 10–20 min</span>
              <span className="muted small">You&apos;ll be told which room to go to</span>
            </div>
          </div>
        </section>

        <section className="home-strip" aria-label="At a glance">
          <div>
            <strong>No receptionist</strong>
            <span>Patients check themselves in by QR code</span>
          </div>
          <div>
            <strong>No app</strong>
            <span>Everything works in the phone&apos;s browser</span>
          </div>
          <div>
            <strong>Your clinic only</strong>
            <span>Every record is scoped to your clinic</span>
          </div>
          <div>
            <strong>Honest estimates</strong>
            <span>Wait times are ranges, never promises</span>
          </div>
        </section>

        <section id="how-it-works" className="home-section" aria-labelledby="how-title">
          <h2 id="how-title" className="home-h2">How a patient moves through the clinic</h2>
          <p className="home-sub">Six steps from the waiting-room door to the doctor&apos;s room, with nobody at a desk.</p>
          <ol className="home-steps">
            {PATIENT_STEPS.map((step, i) => (
              <li key={step.title} className="home-step">
                <span className="home-step-number" aria-hidden="true">
                  {i + 1}
                </span>
                <h3>{step.title}</h3>
                <span className="home-step-who">{step.who}</span>
                <p>{step.text}</p>
              </li>
            ))}
          </ol>
        </section>

        <section className="home-section home-section-alt" aria-labelledby="flow-title">
          <h2 id="flow-title" className="home-h2">One click, every screen</h2>
          <p className="home-sub">What happens when the doctor calls the next patient.</p>
          <ol className="home-flow">
            {REQUEST_FLOW.map((stage) => (
              <li key={stage.label}>
                <strong>{stage.label}</strong>
                <span>{stage.detail}</span>
              </li>
            ))}
          </ol>
        </section>

        <section id="roles" className="home-section" aria-labelledby="roles-title">
          <h2 id="roles-title" className="home-h2">Built for everyone in the clinic</h2>
          <div className="home-grid home-grid-4">
            {ROLES.map((r) => (
              <article key={r.role} className="home-card">
                <h3>{r.role}</h3>
                <ul>
                  {r.points.map((p) => (
                    <li key={p}>{p}</li>
                  ))}
                </ul>
              </article>
            ))}
          </div>
        </section>

        <section id="features" className="home-section home-section-alt" aria-labelledby="features-title">
          <h2 id="features-title" className="home-h2">What&apos;s inside</h2>
          <div className="home-grid home-grid-3">
            {FEATURES.map((f) => (
              <article key={f.title} className="home-card">
                <h3>{f.title}</h3>
                <p>{f.text}</p>
              </article>
            ))}
          </div>
        </section>

        <section id="pipeline" className="home-section" aria-labelledby="pipeline-title">
          <h2 id="pipeline-title" className="home-h2">Under the hood</h2>
          <p className="home-sub">How ClinicIT is built, tested and delivered.</p>
          <div className="home-arch" aria-label="Request path">
            <div className="home-arch-node">
              <strong>Web app</strong>
              <span>Next.js on Vercel</span>
            </div>
            <span className="home-arch-arrow" aria-hidden="true">→</span>
            <div className="home-arch-node">
              <strong>API</strong>
              <span>Spring Boot (Java 21) on Vercel</span>
            </div>
            <span className="home-arch-arrow" aria-hidden="true">→</span>
            <div className="home-arch-node">
              <strong>Database</strong>
              <span>PostgreSQL on Supabase</span>
            </div>
          </div>
          <p className="muted small home-center">
            HTTPS for every request, WebSocket for live updates, and an optional wait-time model behind the API.
          </p>
          <h3 className="home-h3">Delivery pipeline</h3>
          <ol className="home-flow">
            {DELIVERY.map((stage) => (
              <li key={stage.label}>
                <strong>{stage.label}</strong>
                <span>{stage.detail}</span>
              </li>
            ))}
          </ol>
        </section>

        <section className="home-section home-section-alt" aria-labelledby="responsible-title">
          <h2 id="responsible-title" className="home-h2">Operational, not clinical</h2>
          <p className="home-sub home-narrow">
            ClinicIT organises the day: check-in, the queue and the figures. It never diagnoses, prescribes,
            reorders patients by condition or refuses care. The doctor is chosen from the visit type the patient picks,
            never from what they write about their health. Self check-in is not for emergencies, and the page says so.
            Wait estimates are advisory and labelled that way.
          </p>
        </section>

        <section className="home-cta" aria-labelledby="cta-title">
          <h2 id="cta-title">Ready for today&apos;s patients?</h2>
          <p>Doctors and admins sign in with the account their clinic admin created.</p>
          <HomeActions />
        </section>
      </main>

      <footer className="home-footer">
        <span className="brand">
          <BrandMark /> ClinicIT
        </span>
        <span className="muted small">Patients: scan the check-in QR code in your clinic to join the queue.</span>
      </footer>
    </div>
  );
}
