import Link from "next/link";
import { HomeActions } from "./HomeActions";

const PATIENT_STEPS = [
  {
    title: "Book or walk in",
    who: "Reception",
    text: "Book a free slot from the doctor's real hours, or check a walk-in straight into the queue with one click.",
  },
  {
    title: "Arrive and check in",
    who: "Reception",
    text: "Mark the patient as arrived. The appointment, the queue and the day's counts stay in step.",
  },
  {
    title: "Get a token",
    who: "Patient",
    text: "Each patient gets the next token for the day and a private status link. No app, no account.",
  },
  {
    title: "Wait anywhere",
    who: "Patient",
    text: "The status page shows the token being served, how many are ahead and a wait range, and updates by itself.",
  },
  {
    title: "Called, live",
    who: "Reception or doctor",
    text: "Calling the next patient updates the doctor's screen, the desk and the patient's phone together.",
  },
  {
    title: "Consult and close",
    who: "Doctor",
    text: "Start and complete the consultation. Every step is recorded for the day's figures.",
  },
] as const;

const ROLES = [
  {
    role: "Reception",
    points: [
      "Find or register patients in seconds",
      "Book slots, walk-ins, reschedules and cancellations",
      "Every doctor's queue on one live screen",
      "Call, skip, requeue or record a no-show",
    ],
  },
  {
    role: "Doctors",
    points: [
      "The current patient, the reason for the visit, who is next",
      "Call next, start and complete in one click each",
      "Only their own queue, always current",
    ],
  },
  {
    role: "Patients",
    points: [
      "A private link on their phone: no app, no sign-up",
      "Their token, the token being served and a wait range",
      "“You’re next” and “It’s your turn” without refreshing",
    ],
  },
  {
    role: "Clinic admins",
    points: [
      "Staff logins and roles, the clinic's name",
      "Doctors' weekly hours, breaks, appointment length and leave",
      "Waits, completions, no-shows, doctor workload and trends",
    ],
  },
] as const;

const FEATURES = [
  { title: "Live token queue", text: "Tokens are allocated atomically, and one patient is with a doctor at a time. Every screen stays in sync." },
  { title: "Real schedules", text: "Bookings respect each doctor's hours, breaks and leave. Walk-ins never take a booked slot." },
  { title: "Wait estimates", text: "An approximate range from the live queue and the clinic's own history. Always a range, never a promise." },
  { title: "No-show reminders", text: "An advisory flag suggests a reminder call, based only on the patient's own attendance. It never blocks care." },
  { title: "Clinic figures", text: "Average and median wait, consultations, no-show rate, hourly queue and doctor workload, from recorded history." },
  { title: "Secure by default", text: "Role-based access, server-side sessions, every query scoped to your clinic, and nothing sensitive in logs." },
] as const;

const REQUEST_FLOW = [
  { label: "Reception clicks “Call #24”", detail: "Signed-in staff only" },
  { label: "One database transaction", detail: "Queue, appointment and history change together, or not at all" },
  { label: "Change recorded, then announced", detail: "Only committed changes are ever sent" },
  { label: "Every screen updates", detail: "Doctor and desk live over WebSocket; the patient's page on its next check" },
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
          ClinicIT
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
            <p className="home-eyebrow">Queue and appointment management for outpatient clinics</p>
            <h1 id="home-title">Run the clinic day without a crowd at the desk.</h1>
            <p className="home-lead">
              Book appointments and walk-ins, run a live token queue for reception and doctors, and let patients follow
              their turn on their own phone.
            </p>
            <HomeActions />
            <p className="muted small">Patients: open the link the clinic sent you. No account is needed.</p>
          </div>

          <div className="home-hero-visual" aria-hidden="true">
            <div className="home-mock home-mock-desk">
              <div className="home-mock-head">
                <span>Dr. Sharma</span>
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
              <span className="home-mock-button">Call #25</span>
            </div>
            <div className="home-mock home-mock-phone">
              <span className="label">Your token</span>
              <span className="token token-xl">#27</span>
              <strong>2 patients ahead of you</strong>
              <span className="muted small">Estimated wait 10–20 min</span>
              <span className="muted small">Updates automatically</span>
            </div>
          </div>
        </section>

        <section className="home-strip" aria-label="At a glance">
          <div>
            <strong>Live</strong>
            <span>Staff screens update within about a second</span>
          </div>
          <div>
            <strong>No app</strong>
            <span>Patients use a private web link</span>
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
          <p className="home-sub">Six steps, from the first contact to the end of the consultation.</p>
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
          <p className="home-sub">What happens when the desk calls the next patient.</p>
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
            ClinicIT organises the day: appointments, the queue and the figures. It never diagnoses, prescribes,
            reorders patients by condition or refuses care. Wait estimates and the no-show flag are advisory and are
            labelled that way wherever they appear.
          </p>
        </section>

        <section className="home-cta" aria-labelledby="cta-title">
          <h2 id="cta-title">Ready for today&apos;s patients?</h2>
          <p>Staff sign in with the account their clinic admin created.</p>
          <HomeActions />
        </section>
      </main>

      <footer className="home-footer">
        <span className="brand">ClinicIT</span>
        <span className="muted small">Patients: use the private link from your clinic to follow your place in the queue.</span>
      </footer>
    </div>
  );
}
