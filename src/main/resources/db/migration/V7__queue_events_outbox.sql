-- Phase 4: transactional outbox for real-time queue notifications.
--
-- Each queue state change inserts one row here in the same transaction as the change
-- itself, so an event exists if and only if the change committed. After commit the row
-- is pushed to WebSocket subscribers and marked published; a poller re-sends rows left
-- unpublished (e.g. after a crash), so delivery is at-least-once.
create table queue_events (
    id bigserial primary key,
    event_id uuid not null,
    clinic_id uuid not null references clinics(id),
    doctor_id uuid not null,
    queue_entry_id uuid not null,
    appointment_id uuid not null,
    queue_date date not null,
    token_number integer not null,
    type varchar(40) not null,
    status varchar(32) not null,
    previous_status varchar(32),
    entry_version bigint not null,
    occurred_at timestamp with time zone not null,
    published_at timestamp with time zone,

    constraint uk_queue_event_id unique (event_id),
    constraint fk_queue_event_entry foreign key (queue_entry_id) references queue_entries(id),
    constraint fk_queue_event_doctor_same_clinic
        foreign key (doctor_id, clinic_id) references doctor_profiles (id, clinic_id),
    constraint ck_queue_event_type check (type in (
        'PATIENT_JOINED_QUEUE', 'PATIENT_CALLED', 'PATIENT_STARTED_CONSULTATION',
        'PATIENT_COMPLETED', 'PATIENT_SKIPPED', 'PATIENT_REQUEUED', 'PATIENT_NO_SHOW'))
);

-- The retry poller scans only what is still pending.
create index idx_queue_events_pending on queue_events (id) where published_at is null;
create index idx_queue_events_published on queue_events (published_at) where published_at is not null;
