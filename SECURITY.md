# Security

## Reporting a vulnerability

Please do not open a public issue. Use GitHub's private reporting instead: open the repository's **Security** tab
and choose **Report a vulnerability**. Include a description, steps to reproduce and the impact you observed. Never
include real patient data. Please allow reasonable time for a fix before disclosing an issue.

## Security model in brief

**Access**
- Staff sign in with server-side sessions that can be revoked (sign-out, password change and disabling an account
  end every session at once). Roles: admin, front desk (receptionist) and doctor.
- Every endpoint declares the roles it allows; a test fails the build if one does not. Doctors only reach their
  own queue.
- Sign-in attempts are throttled per account and address.

**Clinic isolation**
- Every query starts from the caller's clinic, and composite foreign keys make the database reject cross-clinic
  references, so one clinic cannot read or change another's patients, doctors or queues.

**Public pages**
- The self check-in page and the patient status page need no login. They are rate limited per address, return no
  doctor or patient details beyond the patient's own token, and use unguessable codes; status links stop working
  after the day.

**Production configuration**
- With `SPRING_PROFILES_ACTIVE=prod` the API refuses to start with unsafe settings (development passwords, wildcard
  or plain-HTTP CORS, demo data, the development message provider) and lists every problem.
- Secrets live only in the hosting provider's environment; nothing secret is compiled into the web app.
- Strict Content-Security-Policy and security headers on the web app; nothing sensitive is written to logs.

The full model, with the reasoning behind each decision, is in [docs/SECURITY.md](docs/SECURITY.md).
