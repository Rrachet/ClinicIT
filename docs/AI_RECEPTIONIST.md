# AI receptionist

Patients scan the clinic's QR code and **chat** with a receptionist powered by Claude. It checks them in if they are at
the clinic, or books a free time for later. It never changes anything by itself: every action goes through the same
services and rules as the rest of ClinicIT. Once booked or queued, the doctor takes it from there.

## The flow

1. The admin prints the poster from **Admin → Check-in**, choosing **The AI receptionist chat**. The QR code opens
   `/talk/{code}` (the check-in form stays at `/check-in/{code}`).
2. The patient chats in their own words and language ("my son has a fever, can we come tomorrow evening?").
3. Claude uses four tools:

   | Tool | What ClinicIT does |
   |---|---|
   | `list_visit_types` | Returns the clinic's active visit types and whether a doctor for each is live now. |
   | `find_slots` | Returns free slots (today up to 7 days ahead) of the doctors who see that visit type, from their real hours, breaks, leave and existing bookings. |
   | `book_appointment` | Books a slot `find_slots` would offer, under the doctor lock, as a confirmed appointment. One booking per patient per day. |
   | `check_in_now` | The self check-in: queues the patient with the least busy live doctor for the visit type, or checks in their booking for today. |

4. **Checked in:** the chat shows the token, doctor and room, with a button to the patient's live queue page.
   **Booked:** it shows the day, time and doctor. On arrival the patient scans again and is queued for that booking,
   whether or not the doctor has gone live yet.
5. **The doctor** sees bookings in today's list (refreshed every minute) and the patient in their queue on
   arrival, with the visit type and the patient's note as the reason.

## Rules the model works under

- **It proposes; ClinicIT decides.** Tool inputs are validated, and the services apply every booking rule. A
  rejected call goes back to the model as an error result, which it explains to the patient.
- **The patient chooses the visit type.** The model lists the clinic's visit types and may suggest the obvious one
  (for example the children's one for a child); the patient confirms. It never routes by how serious something sounds.
- **Not clinical.** The instructions forbid diagnosis, medical advice and judging severity. Anything that may be an
  emergency is answered with "tell clinic staff now or call 112", and booking stops. The page repeats this notice.
- **Minimal data.** Only name, phone, visit type and an optional note are asked for.
- **Confirmation first.** The model repeats a one-line summary and waits for a yes before booking or checking in.
- **Prompt injection.** The patient's text is treated as a message to a receptionist. Because the only actions are
  the four tools above, even a manipulated model can do no more than a patient could with the check-in form.

## How it is built

- **Code:** package `com.clinicit.receptionist`, using the official Anthropic Java SDK (`anthropic-java`) and the
  Messages API with client tools (strict schemas).
- **Model:** `claude-opus-5-5` by default (`CLINICIT_RECEPTIONIST_MODEL`), at effort `low` for quick replies
  (`CLINICIT_RECEPTIONIST_EFFORT`).
- **Refusals:** declined requests are retried server-side on Anthropic's recommended fallback (`fallbacks: "default"`).
- **Prompt caching:** tools and the system prompt are byte-stable, and automatic caching covers the conversation.
- **Transcript:** stored in `receptionist_conversations` after each turn and replayed **unchanged** on the next
  (thinking blocks included), so the model keeps its context.
- **Failures:** a turn that is declined, cut off or fails is not stored unless it already booked or checked someone
  in, so the patient can simply rephrase. If Claude can't be reached, the API answers `503 RECEPTIONIST_UNAVAILABLE`
  and the page points to the check-in form.
- **Retention:** conversations hold names and phone numbers, so they are deleted a day after their last message.
- **Abuse and cost limits:**
  - messages are rate limited per client address (80 per 10 minutes);
  - a conversation is capped at 30 messages of up to 500 characters each;
  - the model may chain at most 6 tool calls per reply.

## Switching it on

Set `ANTHROPIC_API_KEY` on the API (for the live deployment: the Vercel project `clinic-it-api`, Production,
type **Sensitive**), then redeploy. Without a key the chat page says chat isn't available and links to the check-in
form, and the admin poster can only point at the form.

## API

- `GET  /api/v1/public/receptionist/{code}`: `{ clinicName, enabled, greeting }`
- `POST /api/v1/public/receptionist/{code}/messages` `{ conversationId?, message }` →
  `{ conversationId, reply, actions: [{ type: QUEUED | BOOKED, statusCode, tokenNumber, doctorName, room, startsAt }] }`

## Tests

`ReceptionistIntegrationTest` runs the real SDK against a fake Messages API (`FakeClaudeServer`). It checks:
- a full booking through `find_slots` and `book_appointment`, and the exact request: model, effort, strict tools in a
  fixed order, caching and fallbacks;
- checking in, without exposing the patient's status code to the model;
- transcript replay with thinking blocks;
- declined turns being forgotten, and tool errors reaching the model;
- a booked patient being queued on arrival;
- the API being unreachable.

`ReceptionistChat.test.tsx` covers the chat page.
