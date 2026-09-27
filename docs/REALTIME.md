# Real-Time Queue (Phase 4)

Screens stay in sync without refreshing. When the receptionist calls token #24, the doctor's screen and the
waiting-room display update within milliseconds. WebSocket messages are **notifications of committed state
changes**. They are not a second way of performing queue operations: every change still goes through the
authenticated REST API.

## Flow

```
Receptionist                         POST /api/v1/queues/call-next      (REST, authenticated)
    │
    ▼
QueueService.callNext  ──────────────────────────  one PostgreSQL transaction ──────────────┐
    │  lock doctor, pick next, update queue entry + appointment                             │
    │  QueueEventRecorder.record(entry, previousStatus)                                      │
    │      └─ INSERT INTO queue_events (outbox row)                                          │
    │      └─ raise QueueEventRecorder.Recorded (in-memory, held until commit)               │
    ▼                                                                                        │
COMMIT ◄─────────────────────────────────────────────────────────────────────────────────────┘
    │   (rollback ⇒ no outbox row and no Recorded event ⇒ nothing is ever announced)
    ▼
QueueEventDispatcher.afterCommit            @TransactionalEventListener(AFTER_COMMIT)
    │  QueueEventPublisher.publish(event)   ← port owned by the queue module
    │  UPDATE queue_events SET published_at  (own transaction)
    ▼
StompQueueEventPublisher (realtime module)  → in-memory STOMP broker
    ├── /topic/clinic/{clinicId}/queue                    → reception / admin screens
    └── /topic/clinic/{clinicId}/doctor/{doctorId}/queue  → that doctor's screen, reception displays

QueueEventDispatcher.retryPending (every 5 s): re-sends outbox rows still unpublished 10 s after they occurred
```

### Why an outbox

The requirement is that a committed change is never misrepresented, and nothing uncommitted is ever announced.

| Approach | Problem |
|---|---|
| Send to the WebSocket inside `QueueService` | The transaction can still roll back after sending, so clients would be told about a state that never existed. |
| `AFTER_COMMIT` listener only | Correct, but a crash or broker error between commit and send loses the event silently. |
| **Outbox row in the same transaction + after-commit send + retry poller** (chosen) | An event exists if and only if the change committed. The fast path costs nothing extra, and anything not confirmed is retried. |

The result is **at-least-once** delivery. Clients dedupe on `eventId`.

Delivery can fail without breaking anything. If the publisher throws, the queue change is already committed and
stays committed; the row simply stays `published_at IS NULL` until the poller retries it. Published rows are purged
after 7 days (`clinicit.queue.events.retention`); the queue tables themselves remain the history.

Kafka, a message broker, or separate services would add operational cost with no benefit at this scale. The outbox
is one table, and the in-memory STOMP broker is part of Spring.

### Module boundaries

- `queue` owns the outbox (`QueueEventRecord`, `QueueEventRecorder`, `QueueEventDispatcher`) and defines the port
  `QueueEventPublisher`. It knows nothing about WebSockets.
- `realtime` implements the port (`StompQueueEventPublisher`) and holds all WebSocket configuration and security.
- `identity` raises `SessionsRevoked` when tokens stop being valid. `realtime` listens so it can close sockets.

`QueueService` has exactly one line per operation for notifications: `events.record(entry, previousStatus, now)`.

## Event contract

JSON body of every message on both topics:

```json
{
  "eventId": "6c1f0a9e-…",            // unique; dedupe on this (delivery is at-least-once)
  "sequence": 1287,                    // outbox id; increases per queue entry
  "type": "PATIENT_CALLED",
  "occurredAt": "2026-03-10T05:31:04Z",
  "clinicId": "…",
  "doctorId": "…",
  "queueDate": "2026-03-10",           // clinic-local day
  "queueEntryId": "…",
  "appointmentId": "…",
  "tokenNumber": 24,
  "status": "CALLED",                  // the entry's status after the change
  "previousStatus": "WAITING",         // null for PATIENT_JOINED_QUEUE
  "entryVersion": 1                    // the entry's version after the change: 0 at join, +1 per change
}
```

| `type` | Transition |
|---|---|
| `PATIENT_JOINED_QUEUE` | → WAITING (new entry) |
| `PATIENT_CALLED` | WAITING → CALLED |
| `PATIENT_STARTED_CONSULTATION` | CALLED → IN_CONSULTATION |
| `PATIENT_COMPLETED` | IN_CONSULTATION → COMPLETED |
| `PATIENT_SKIPPED` | WAITING / CALLED → SKIPPED |
| `PATIENT_REQUEUED` | SKIPPED → WAITING |
| `PATIENT_NO_SHOW` | SKIPPED → NO_SHOW |

The type is derived from the transition. Callers cannot choose it.

**No patient data.** An event has no name, phone number, date of birth or reason for visit. That is enough to move
token #24 between columns and show "now serving #24". A staff screen that shows names fetches them once through
the authenticated REST API (`GET /api/v1/queues/today`) when it sees an entry it doesn't know. A test asserts the
exact set of fields and that the patient's name, phone and reason never appear.

### Ordering and client rules

A single queue entry's changes are serialised by its row lock, so their `entryVersion` and `sequence` values
increase strictly. Delivery **order across threads is not guaranteed**, however. Two changes to the same entry can
commit in order and still be sent in the opposite order, because each is sent by its own request thread after
commit. Publishing while holding the lock would avoid that, but it would mean publishing before commit. Clients
therefore follow three rules:

1. Ignore an event whose `eventId` you have already seen.
2. Apply an event only if its `entryVersion` is greater than the version you hold for that `queueEntryId`.
3. When connecting, and after any reconnect, load `GET /api/v1/queues/today?doctorId=…` first. Events carry only
   changes, and anything that happened while you were disconnected is not replayed.

The concurrency test drives 16 patients through join, skip and requeue while doctors call, start and complete in
parallel. It checks that every committed change arrives exactly once. It checks that each entry's versions are
contiguous. And it checks that applying the rules above reproduces the database state exactly.

## Connecting and subscribing

- **Endpoint:** `ws(s)://<host>/ws`, plain WebSocket, STOMP 1.2. There is no SockJS fallback.
- **Origins:** the handshake accepts the same exact origins as REST CORS (`CLINICIT_CORS_ALLOWED_ORIGINS`), and is
  same-origin only when that list is empty.
- **CONNECT:** must carry the login token as a STOMP header, `Authorization: Bearer <accessToken>`. Browsers cannot
  set headers on the WebSocket handshake itself, so the handshake URL is public and authentication happens at
  CONNECT. Without a valid token the server replies with an ERROR frame and closes.
- **Heartbeats:** 10 s both ways. Messages from clients are limited to 16 KB. Slow consumers are dropped rather
  than buffered indefinitely.

The reference client is `frontend/src/realtime/queueFeed.ts` together with `frontend/src/queue/queueStore.ts`.
Example with `@stomp/stompjs`:

```js
const client = new Client({
  brokerURL: "wss://api.clinicit.example/ws",
  connectHeaders: { Authorization: `Bearer ${accessToken}` },
  onConnect: () => client.subscribe(`/topic/clinic/${me.clinicId}/doctor/${me.doctorProfileId}/queue`,
                                    msg => apply(JSON.parse(msg.body))),
});
```

## Security

All rules live in one place, `StompSecurityInterceptor`, and apply to every frame a client sends. Everything not
listed below is denied.

| Frame | Rule |
|---|---|
| CONNECT | A valid, unexpired, unrevoked token for an enabled user. The connection is bound to that staff member. |
| SUBSCRIBE `/topic/clinic/{c}/queue` | `c` must equal the connected user's clinic, and the role must be ADMIN or RECEPTIONIST. |
| SUBSCRIBE `/topic/clinic/{c}/doctor/{d}/queue` | `c` must equal the user's clinic, and `d` must be a doctor of that clinic. A DOCTOR may only use their own `d`. |
| SUBSCRIBE anything else | Denied, including `/topic/**`, `/user/**`, `/app/**` and malformed ids. |
| SEND, ACK, NACK, BEGIN, COMMIT, ABORT | Always denied. There are no inbound message handlers. |

- **The `clinicId` in a destination is compared, never trusted.** It must match the clinic taken from the token.
- **A denied frame** gets a STOMP ERROR frame and the connection is closed.
- **The token is re-validated on every SUBSCRIBE.**
- **Connections do not outlive their session:**
  - Logout, user disable and password change raise `SessionsRevoked`. The affected sockets are closed right after
    that transaction commits.
  - A sweep every minute (`clinicit.realtime.revalidate-interval`) closes connections whose token has expired.
- **Patient-facing screens do not get the staff feed.** There is no anonymous topic; an unauthenticated client
  cannot even connect. A waiting-room TV runs as a logged-in front-desk account on a doctor topic.
- **The public patient status page (Phase 5)** polls its own sanitised endpoint,
  `GET /api/v1/public/queue-status/{code}`. It never touches these topics. See docs/FRONTEND.md.

## Scaling note

The in-memory broker delivers only to clients connected to the same application instance. ClinicIT runs as one
instance today. Running several would need a shared fan-out:

- Spring's STOMP broker relay to RabbitMQ or ActiveMQ (a configuration change in `WebSocketConfig`), or
- PostgreSQL `LISTEN/NOTIFY` from the outbox.

The outbox and the event contract stay the same either way.

## Tests

| Suite | Proves |
|---|---|
| `RealtimeQueueIntegrationTest` | HTTP actions change the database and emit the matching event, with the correct fields and increasing versions. Skip, requeue and no-show events. The exact field set, with no patient data. Rejected actions and **work rolled back after the event was recorded** emit nothing. A delivery failure keeps the change and leaves the row pending; the poller re-sends it with the same `eventId`. Retention purge. A concurrent workload yields exactly one event per change and replays to the database state. |
| `RealtimeSecurityIntegrationTest` | CONNECT without or with a bad token is refused. Other clinics' topics are refused, including your own clinic path with another clinic's doctor. Another clinic's activity never reaches you. Doctors are limited to their own topic. Front desk sees the whole clinic. Unknown topics and SEND frames are refused. Logout, user disable and password change close the socket. Expiry closes it via the sweep. The handshake from an unknown origin is refused. |

Each of these protections was broken on purpose and a test failed:
- publishing before commit instead of after
- no clinic check on subscribe
- doctors not limited to their own topic
- SEND frames allowed
- revocation not propagated to open sockets
