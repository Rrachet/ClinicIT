# Hosted preview mode

A public, try-it-yourself deployment of ClinicIT that shows the fictional [demo clinic](DEMO.md) without risking
anything real. It is **off by default**; the `prod` profile still refuses demo data unless this switch is set.

## What it does

| Setting | Effect |
|---|---|
| `CLINICIT_PREVIEW_ENABLED=true` | Lets the `prod` profile start with demo data. Without it, `CLINICIT_DEMO_ENABLED` is refused. |
| `CLINICIT_DEMO_ENABLED=true` | Required with preview mode. Loads the demo clinic on an **empty** database (never touches an existing clinic). |
| `CLINICIT_DEMO_PASSWORD` | The shared password of every demo login, 12+ characters. In a preview it is **published on purpose**, so choose one you use nowhere else. |
| `CLINICIT_NOTIFICATIONS_ENABLED=false` | Required. A preview never sends messages. |
| `CLINICIT_PREVIEW_ALLOW_WRITES` | Optional, default off. See below. |

The API refuses to start if preview mode is set without demo data or with notifications on, and lists every problem
at once ([OPERATIONS.md](OPERATIONS.md)).

## Read-only by default

With preview mode on and `CLINICIT_PREVIEW_ALLOW_WRITES` unset, anyone can sign in with a demo login and look around
(queue boards, analytics, schedules, wait estimates), but every API request that would change data is refused:

```json
{ "status": 403, "code": "PREVIEW_READ_ONLY",
  "message": "This is a read-only preview with fictional data. Run the local Docker Compose stack to try changes (see the README)." }
```

Signing in and out still work, and reads are untouched. The guard runs after authentication, so an unauthenticated
write still answers 401. Setting `CLINICIT_PREVIEW_ALLOW_WRITES=true` lets signed-in users change the demo data;
nothing resets it, so a public preview with writes on drifts and can be defaced. Recreate the database to reset it.

## Deploying it

Follow [DEPLOYMENT.md](DEPLOYMENT.md) and add these to the API service's environment:

```text
CLINICIT_PREVIEW_ENABLED=true
CLINICIT_DEMO_ENABLED=true
CLINICIT_DEMO_PASSWORD=<12+ characters, published on purpose>
```

- **Use an empty database.** Demo data loads only when no clinic exists; on a database that already holds data it is
  skipped and the preview would show that data instead. Never point a preview at a database with real records.
- The demo loader runs before the bootstrap-admin step, so leaving the bootstrap variables set is harmless: the
  bootstrap is skipped once the demo clinic exists.
- Keep the three variables set. The configuration check runs on every start.

## Not covered

- The web app shows the API's message when a write is refused but has no "preview" banner yet.
- There is no automatic reset. A scheduled database reset would be the next step for a write-enabled preview.
