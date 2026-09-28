# No-show Risk Flag

An **advisory** flag on booked appointments ("No-show risk: Elevated") that suggests a reminder call. It is a
simple, explainable rule on the patient's own attendance at this clinic. It is **not** a trained model and makes no
accuracy claim; instead, the product measures on the clinic's own history whether the flag helps.

## What it must never do

- Refuse, cancel or reschedule an appointment, or change anything else. No endpoint other than the flag's own two
  reads looks at it, and a test books and serves a flagged patient like anyone else.
- Diagnose anything, or claim certainty. The wording is "No-show risk: Elevated", with the reason in words.
- Use who the patient is. Age, sex, name, phone, address, reason for visit and the doctor are not inputs.
- Be shown to patients or doctors. The front desk sees it (for reminder calls), admins see the evaluation.

A pattern of missed appointments can reflect hardship: transport, work or caring duties. The only intended use is
a friendlier nudge (a reminder, or offering a slot that suits the patient better).

## The rule

> **Elevated** when the patient has at least **3** earlier booked appointments with a known outcome (of their
> last **10**), missed at least **2** of them, and their miss rate is at least **30%** and at least **twice the
> clinic's** (over the previous 180 days).

- **Not enough history:** with fewer than 3 known outcomes the result is *Unknown* and nothing is shown.
- **Otherwise:** *Typical*, which is also not shown.
- **Counts:** "missed" means marked no-show without ever arriving; "kept" means the patient arrived. Walk-ins (here
  by definition), cancellations and appointments without an outcome yet are left out.
- **Code:** `NoShowRiskRule` holds the whole rule; `NoShowRiskRuleTest` covers each threshold.

## No leakage

Every judgement is made **as of a moment**, using only:
- appointments scheduled before that moment, and
- outcomes *recorded* before it (the time of the `ARRIVED` or `NO_SHOW` event in the immutable history).

For the front desk, the moment is **now**. In the evaluation it is **the start of the appointment's day**, when
reception first sees the day's list. The appointment being judged is never part of its own history. A no-show
recorded late (say, two days after the appointment) only counts from when it was recorded.
`aNoShowRecordedLateIsNotKnownBeforeItWasRecorded` proves it, and fails if the recording-time condition is removed
(checked by mutation).

## Temporal evaluation

`GET /api/v1/no-show-risk/evaluation?from=&to=` (admin; default: the 90 days before today) replays the rule over
past days, each judged as of that morning, and compares the flags with what happened:

| Figure | Meaning |
|---|---|
| `missRate` | misses ÷ booked appointments with an outcome: the base rate a flag has to beat |
| `flaggedMissRate` | misses among flagged appointments (precision) |
| `notFlaggedMissRate` | misses among judged, unflagged appointments |
| `recall` | share of all misses that were flagged |
| `lift` | `flaggedMissRate ÷ missRate`; 1 means the flag adds nothing |
| `insufficientHistory` | appointments not judged |
| `enoughData` | at least 20 flagged and 10 missed; below that the screen gives no verdict |

The admin analytics screen shows this as "No-show risk flag · last 90 days". It says plainly when the flag does
not help, or when there is too little data to tell.

**On the demo clinic** ([DEMO.md](DEMO.md)), missed appointments are assigned at random, so no patient has a real
pattern and the flag has nothing to find. Measured on a freshly loaded demo (28 Sep 2026): 282 booked appointments
with an outcome, 11% missed; 19 flagged, of which 1 was missed (lift 0.46). The screen reports "too few to judge".
That is the honest result: the flag can only be useful where the clinic's own history shows it is.

## API

| Endpoint | Roles | |
|---|---|---|
| `GET /api/v1/no-show-risk?date=` | front desk (admin, receptionist) | flags for the day's booked and confirmed appointments, walk-ins excluded: `level`, the counts, and a `reason` such as "Missed 3 of their last 5 appointments here" |
| `GET /api/v1/no-show-risk/evaluation?from=&to=` | admin | the evaluation above; at most 366 days |

Both are read-only and clinic-scoped. In the reception console, a failed flag request just means no flags.

## Tests

- `NoShowRiskRuleTest`: each threshold, and the Unknown / Typical / Elevated boundaries.
- `NoShowRiskIntegrationTest`:
  - today's flags from a hand-built history;
  - the evaluation's every figure, worked out by hand;
  - a judged day never seeing later days;
  - a late-recorded no-show not counting before it was recorded;
  - roles, clinic isolation, read-only access, and that a flagged patient is booked and served as usual.
- Frontend:
  - the badge and its reason, and that the actions are unchanged;
  - no flags when the check fails;
  - the evaluation panel, including the "too little data" state.
