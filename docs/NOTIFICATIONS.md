# Patient Notifications (Phase 6)

Patients are messaged automatically at the few moments that matter, including their queue-status link when they
join the queue. Reception no longer has to copy the link by hand.

## Architecture

```
AppointmentService.confirm ──► AppointmentConfirmed ─┐   (domain events, published inside the
QueueService (any change) ───► QueueEventRecorder     │    clinic/queue transaction)
                                 .Recorded ───────────┤
                                                      ▼
                                          NotificationTriggers        catches everything: never breaks the caller
                                                      ▼
                                          NotificationService         decides what is worth a message; INSERTs rows
                                                      │                in the SAME transaction (ON CONFLICT DO NOTHING)
                                                COMMIT│   (rollback ⇒ no rows ⇒ nothing sent)
                                                      ▼
                                          NotificationDispatcher      after commit, on its own small thread pool
                                            │  claim row (atomic UPDATE) → expired? → provider.send → SENT
                                            │  failure → PENDING + backoff, or FAILED
                                            └─ retry poller (10 s) for due PENDING rows
                                                      ▼
                                          NotificationProvider  (port)
                                            ├── DevelopmentNotificationProvider   records instead of sending (default)
                                            └── SMS / WhatsApp / email vendor      one class each, added later
```

- **Core services don't know notifications exist.** `QueueService` and `AppointmentService` publish domain events
  and nothing else. There is no Twilio, WhatsApp or SMTP code anywhere near them. The `notification` module depends
  on the core modules, never the other way round.
- **Sending is decoupled from the operation.** A message row is the durable *intent* to send, written with the
  change that caused it; that is the outbox pattern again. The vendor call happens after commit, off the request
  thread, so a slow or failing SMS gateway cannot slow down or undo "Call next".
- **Why rows are written in the same transaction, not after commit.** Writing them after commit could lose a
  message if the process stopped in between. Writing them in the same transaction means a message exists if and
  only if the change committed. Two rules keep this safe for the queue operation:
  - The planner uses plain JDBC, and every insert is `ON CONFLICT (dedupe_key) DO NOTHING`. A duplicate event can
    never raise an error, and in PostgreSQL any error aborts the whole transaction. A test shows exactly that
    failure without it: a duplicate near-turn aborted a call-next.
  - `NotificationTriggers` catches every exception from planning (template bugs and the like) and logs it.

## When a patient is messaged

| Type | When | Once per | Valid until | Text |
|---|---|---|---|---|
| `APPOINTMENT_CONFIRMED` | appointment confirmed **more than 30 min before** its time (so walk-ins and imminent visits get no redundant message) | appointment | the appointment time | `City Clinic: your appointment on 10 Mar at 16:00 is confirmed.` |
| `PATIENT_JOINED_QUEUE` | checked into the queue | queue entry | end of the queue day (when the link stops working) | `City Clinic: you're checked in. Your token is #24. Follow your place in the queue: https://…/status/{code}` |
| `PATIENT_NEAR_TURN` | the line moves (someone waiting is called or skipped) and **≤ 1** patient is now ahead (`near-turn-threshold`) | queue entry | 30 min | `City Clinic: token #24, it's nearly your turn. Please stay close to the waiting area. https://…` |
| `PATIENT_CALLED` | called in | each call (a requeued patient called again is messaged again) | 10 min | `City Clinic: token #24, it's your turn. Please go in now.` |

Starting or completing a consultation, skipping, requeueing and no-shows **do not** message the patient.

**Duplicates.** Each row has a unique `dedupe_key`: the appointment for confirmations, the queue entry for
joined/near-turn, and the queue entry plus its version for calls. The same event delivered twice, or reprocessed,
cannot create a second message.

**The status link** is `{CLINICIT_PUBLIC_APP_URL}/status/{statusCode}`, built from the existing unguessable
128-bit code. The public endpoint behind it is unchanged: read-only, no patient data, and 404 after the queue day.

## Privacy

Messages are built only from `NotificationTemplates`, which take only these inputs: clinic name, token, date and
time, and the status link. There is no way to include a patient's name, a doctor's name or specialty, the reason
for visit, a date of birth or anything clinical, and a test checks for each of these.

- **Recipient:** the patient's phone number. It is stored on the row (needed to retry), shown to staff masked
  (`•••3210`), never logged, and never included in `last_error`.
- **Retention:** delivered and failed rows are deleted after 30 days (`retention`), because they hold phone
  numbers.
- **Development provider logs:** only the notification id, channel and length. The body contains the status link,
  which is a capability, so it isn't logged.

## Reliability

| Status | Meaning |
|---|---|
| `PENDING` | waiting to be sent, or waiting for its next retry (`attempts`, `next_attempt_at`, `last_error`) |
| `SENT` | accepted by the provider (`provider`, `provider_message_id`, `sent_at`) |
| `FAILED` | gave up: attempts exhausted (default 5), a permanent provider error, no provider for the channel, or **`EXPIRED`** |

- **Retries:** the delay starts at 30 s and doubles per attempt, capped at 30 min. The poller runs every 10 s.
- **Expiry:** a message that is no longer true is never sent. "Please go in now" an hour late would be harmful.
- **No double sending:** every attempt first *claims* the row with one atomic `UPDATE … RETURNING`. That moves
  `next_attempt_at` forward as a 2-minute lease, and the poller claims with `FOR UPDATE SKIP LOCKED`. The fast
  path and the poller, or several app instances, never send the same row at the same time.
- **At-least-once:** a crash after the vendor accepted a message but before it was marked SENT can resend it.
  Providers receive the notification id as an idempotency key to pass to vendors that support it.
- **Manual retry:** front desk can retry a FAILED message (`POST /api/v1/notifications/{id}/retry`), which gives it
  a fresh set of attempts. Retrying is refused once the message has expired.

## Staff API

| Endpoint | Who | |
|---|---|---|
| `GET /api/v1/notifications?appointmentId=` | ADMIN, RECEPTIONIST | messages for one appointment, recipient masked |
| `POST /api/v1/notifications/{id}/retry` | ADMIN, RECEPTIONIST | retry a FAILED message |

Both are clinic-scoped: another clinic's appointment returns an empty list and its notification id returns 404.
Doctors get 403. In the reception console, each queued patient's **Messages** button shows what was sent, its
status and a Retry button; the link remains available to copy as a fallback.

## Configuration

| Property / env | Default | |
|---|---|---|
| `CLINICIT_NOTIFICATIONS_ENABLED` | `true` | master switch |
| `CLINICIT_NOTIFICATIONS_PROVIDER` | `development` | which provider bean is active |
| `CLINICIT_NOTIFICATIONS_CHANNEL` | `SMS` | `SMS`, `WHATSAPP` or `EMAIL` (patients currently have phone numbers only) |
| `CLINICIT_PUBLIC_APP_URL` | `http://localhost:3000` | frontend URL used in status links (validated as http/https) |
| `clinicit.notifications.near-turn-threshold` | `1` | "nearly your turn" at ≤ this many ahead |
| `…max-attempts` / `retry-base-delay` / `retry-max-delay` / `poll-interval` / `retention` | `5` / `PT30S` / `PT30M` / `PT10S` / `P30D` | |

The **development provider** is the default. It records messages instead of sending them: the row is marked SENT
with provider `development`, and the provider keeps the last 200 messages in memory. It logs a warning at startup
so it is never mistaken for real delivery. `failNextSends(n)` simulates a vendor outage, which the tests use.

## Adding a real provider

1. Implement `NotificationProvider`: `name()`, `supports(channel)` and `send(OutboundMessage)`. The implementation
   should:
   - return the vendor's message id;
   - throw `NotificationDeliveryException(code, retryable)` when a message is not accepted, with
     `retryable = false` for permanent errors such as an invalid number;
   - pass `idempotencyKey()` to the vendor.
2. Annotate it with `@Component` and `@ConditionalOnProperty(name = "clinicit.notifications.provider", havingValue = "twilio")`,
   or whatever name fits, and read its credentials from environment variables. Never commit them.
3. Set `CLINICIT_NOTIFICATIONS_PROVIDER` and `CLINICIT_NOTIFICATIONS_CHANNEL`.

No other code changes: templates, dedupe, retries, expiry, privacy and the staff API stay the same.

## Tests

| Suite | Proves |
|---|---|
| `NotificationIntegrationTest` | Confirmation sent for a later appointment, not for walk-ins. The joined message carries a working status link that expires with the day. Called and near-turn messages, near-turn once per entry. Minor updates (skip, requeue, start, complete) send nothing. **The same event twice gives one message.** **A provider outage keeps the queue change and retries with backoff.** Gives up after max attempts. **An expired message is never sent.** **A rolled-back change sends nothing.** **No name, DOB, reason, specialty or phone in any body.** No provider for the channel means FAILED without a send. Retention purge. Disabled means nothing is created. |
| `NotificationApiIntegrationTest` | Masked recipient; manual retry of FAILED; refusal for an expired message or one that isn't failed; **another clinic sees nothing and cannot retry**; doctors get 403; anonymous callers get 401. |
| `NotificationUnitTest` | **A planning failure never propagates into the clinic operation.** Provider chosen by channel; backoff doubling and cap; templates; status-link URL validation; `OutboundMessage.toString()` never prints the recipient or body. |
| Frontend `StatusLinkDialog.test.tsx` | Messages, statuses and masked numbers shown; retry; refusal message. |
| Browser `workflows.spec.ts` | Walk-in: the queue link and "your turn" messages are recorded as sent, with the right link and no patient name, as seen through the reception UI. |

These tests fail if their safeguard is removed. I checked that by removing each in turn: conflict-free inserts,
the expiry check, the due-time claim, and once-per-entry near-turn.

## Not in this phase

- **Real vendors.** An interface is ready (see above).
- **Patient email addresses.** Patients have phone numbers only.
- **Patient consent and opt-out.** Messaging patients should respect a recorded preference before real sending is
  switched on. This is the main thing to add alongside a real provider.
- **Delivery receipts from vendors.** Today SENT means the vendor accepted the message, not that it was delivered.
