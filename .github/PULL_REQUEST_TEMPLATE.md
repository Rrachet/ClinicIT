## What changes

<!-- A short summary of the change and why it is needed. -->

## How it was tested

- [ ] Backend: `mvn verify` against a PostgreSQL test database
- [ ] Web app: `npm test && npm run typecheck && npm run lint && npm run build` in `frontend/`
- [ ] ML service: `python -m pytest` in `ml/` (if it changed)
- [ ] End to end: `scripts/e2e.sh` (if a workflow changed)
- [ ] Checked on a phone-sized screen (if a patient-facing page changed)

## Checklist

- [ ] No secrets, passwords or real patient data added
- [ ] New queries are scoped to the caller's clinic
- [ ] New endpoints declare their roles; public ones are rate limited and return no staff or patient details
- [ ] Migrations are new numbered files; none that has been applied was edited
- [ ] Nothing diagnoses, prescribes or reorders patients by condition
