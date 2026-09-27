-- Phase 2: queue engine.
--
-- The invariants the queue relies on are enforced here, not only in Java:
--   * one queue entry per appointment                    (existing unique appointment_id)
--   * token unique per clinic per day                    (existing uk_queue_clinic_date_token)
--   * at most one active (CALLED / IN_CONSULTATION) patient per doctor per day
--   * statuses restricted to the known state machine values

-- V2 created clinics without the created_at column that the Clinic entity maps.
alter table clinics
    add column created_at timestamp with time zone not null default now();

-- The queue is served per doctor, so each entry needs its doctor. Copied from
-- the appointment at join time and never changed afterwards.
alter table queue_entries
    add column doctor_id uuid;

update queue_entries q
set doctor_id = a.doctor_id
from appointments a
where a.id = q.appointment_id;

alter table queue_entries
    alter column doctor_id set not null;

alter table queue_entries
    add constraint fk_queue_doctor
    foreign key (doctor_id) references doctor_profiles(id);

alter table queue_entries
    add column skipped_at timestamp with time zone,
    add column updated_at timestamp with time zone not null default now(),
    add column version bigint not null default 0;

alter table queue_entries
    add constraint ck_queue_token_positive check (token_number > 0);

alter table queue_entries
    add constraint ck_queue_status check (
        status in ('WAITING', 'CALLED', 'IN_CONSULTATION', 'COMPLETED', 'SKIPPED', 'NO_SHOW')
    );

alter table appointments
    add constraint ck_appointment_status check (
        status in ('BOOKED', 'CONFIRMED', 'ARRIVED', 'WAITING', 'CALLED',
                   'IN_CONSULTATION', 'COMPLETED', 'CANCELLED', 'NO_SHOW', 'SKIPPED')
    );

-- A doctor sees one patient at a time. The service checks this under a
-- per-doctor lock; this partial index is the backstop if that check is bypassed.
create unique index uk_queue_one_active_per_doctor
    on queue_entries (doctor_id, queue_date)
    where status in ('CALLED', 'IN_CONSULTATION');

-- Serves "call next": lowest waiting token for a doctor on a day.
create index idx_queue_doctor_date_status_token
    on queue_entries (doctor_id, queue_date, status, token_number);

-- One row per clinic per day holding the last issued token. Tokens are taken
-- with a single atomic upsert (see QueueTokenAllocator), so concurrent joins
-- serialise on this row instead of racing on max(token_number) + 1.
create table queue_token_counters (
    clinic_id uuid not null references clinics(id),
    queue_date date not null,
    last_token integer not null check (last_token > 0),
    primary key (clinic_id, queue_date)
);

-- Seed counters for any queues that already exist so new tokens continue after them.
insert into queue_token_counters (clinic_id, queue_date, last_token)
select clinic_id, queue_date, max(token_number)
from queue_entries
group by clinic_id, queue_date;
