# ClinicIT frontend

Next.js app for the reception console, the doctor console, the admin analytics dashboard and the patient
queue-status page.
It talks directly to the ClinicIT Spring Boot API (REST + STOMP WebSocket).

```bash
cp .env.example .env.local   # NEXT_PUBLIC_API_BASE_URL=http://localhost:8080
npm install
npm run dev                  # http://localhost:3000
npm test                     # unit/component tests
npm run typecheck && npm run lint && npm run build
```

End-to-end tests against the real stack: `scripts/e2e.sh` from the repository root.

Architecture, security model, tests and the end-to-end setup: [../docs/FRONTEND.md](../docs/FRONTEND.md).
