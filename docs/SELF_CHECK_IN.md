# Self check-in

Patients check themselves in from their own phone. Nobody at a desk is needed.

## The flow

1. **The doctor goes live.** They sign in, set their room (for example "Room 3") and tap **Go live** on the
   doctor screen. Live lasts for one clinic-local day, so a doctor who forgets to go offline is not live
   tomorrow. **Go offline** stops new patients; anyone already queued stays.
2. **The patient scans the QR code.** The admin prints it from **Admin → Check-in**. It opens
   `/check-in/{code}`, where `code` is the clinic's random public check-in code (not its id).
3. **The patient fills in the form:**
   - name and phone number;
   - a **visit type** (for example "General check-up", "Child (paediatrics)" or "Skin");
   - optionally, a note for the doctor.

   The page says it is not for emergencies.
4. **ClinicIT picks the doctor.** It looks at the live doctors who see that visit type (any live doctor, if the
   visit type allows it). It takes the one with the fewest patients waiting or with them, and on a tie whoever
   went live first. The patient gets a walk-in appointment, already confirmed and arrived, and a token in that
   doctor's queue, all in one transaction.
5. **The patient waits on their status page** (`/status/{statusCode}?checkedIn=1`). It shows:
   - "You're checked in";
   - the token being served and how many are ahead;
   - a wait range.

   It updates every few seconds. The patient can turn on sound and vibration alerts.
6. **The doctor taps "Call next".** The patient's page changes to **"It's your turn"** and shows the room in
   large type.

## Rules

- **The patient's choice decides the doctor.** The visit type picks the doctor. The free-text note is only
  shown to the doctor (stored as `"{visit type}: {note}"` in the appointment's reason). Nothing about the
  patient's health is inferred or used for routing, and nobody is reordered by condition.
- **One place per patient per day.** Checking in again with the same name and phone (spaces, dashes and
  brackets ignored) while still in today's queue returns the same token. No second token is created.
- **No live doctor for the visit type:** `409 NO_DOCTOR_AVAILABLE`, and nothing is created. The page lists
  each visit type and greys out the ones no live doctor sees right now.
- **Working hours are not checked.** Going live is the doctor saying they are seeing patients now.
- **No staff actor.** The history records the appointment as booked, confirmed, arrived and queued with no
  staff member. Analytics and wait estimates work as for any walk-in.

## Who can do what

| Action | Who |
|---|---|
| Go live / offline, set the room (`PUT /api/v1/doctors/{id}/duty`) | the doctor for themselves; admins and receptionists for any doctor |
| Manage visit types (`POST`, `PUT /api/v1/visit-types`) | admins |
| List visit types (`GET /api/v1/visit-types`) | any staff |
| Check-in page and check-in (`GET`, `POST /api/v1/public/check-in/{code}`) | anyone, no login |

## Abuse and privacy

- **Rate limits.** Check-ins are limited per client address: 30 per 10 minutes by default, set with
  `clinicit.self-check-in.window` and `.max-per-ip`. That is enough for a waiting room on one Wi-Fi address.
  The page itself uses the public status limits.
- **No staff or patient data on the public page.** It returns only the clinic's name and which visit types can
  be seen now. It does not return doctor names.
- **What the check-in response returns:** the token, the status code, the doctor's display name and the room.
- **Patient data:** the patient's name and phone are stored like any patient record in that clinic.

## Without a receptionist, who fixes problems?

- **The admin** sees every queue on the Reception screen. They can call, skip, requeue or record a no-show, and
  add a patient who has no phone.
- **On Admin → Check-in** they can take offline a doctor who forgot to.
- **A patient who missed their call** is told to tell the doctor or clinic staff, who can put them back in
  the queue.
