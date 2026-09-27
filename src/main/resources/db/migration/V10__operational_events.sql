-- Phase 7: immutable operational history of appointments and queue entries.
--
-- One row per lifecycle transition, inserted in the same transaction as the transition,
-- so a rolled-back operation leaves no row. Rows are never updated or deleted: the
-- trigger below rejects both, whoever issues them. Analytics are computed from this table,
-- never reconstructed from the current state of appointments or queue entries.
--
-- Unlike queue_events (a short-lived outbox for WebSocket delivery, purged after 7 days),
-- this is the permanent record. It holds no names, phone numbers or clinical text.
create table operational_events (
    id uuid primary key,
    -- Insertion order; breaks ties between events with the same timestamp.
    seq bigserial not null,
    clinic_id uuid not null,
    appointment_id uuid not null,
    queue_entry_id uuid references queue_entries(id),
    doctor_id uuid not null,
    -- Pseudonymous id only (for per-patient no-show history later); never a name.
    patient_id uuid not null,
    event_type varchar(32) not null,
    -- Appointment status before the transition; null for BOOKED.
    previous_status varchar(32),
    occurred_at timestamp with time zone not null,
    -- The staff user who caused it; null for system actions. Not a foreign key: history
    -- must outlive user accounts.
    actor_user_id uuid,
    -- Minimal structured metadata.
    token_number integer,
    scheduled_at timestamp without time zone,

    constraint uk_operational_event_seq unique (seq),
    constraint fk_operational_event_appointment_same_clinic
        foreign key (appointment_id, clinic_id) references appointments (id, clinic_id),
    constraint fk_operational_event_doctor_same_clinic
        foreign key (doctor_id, clinic_id) references doctor_profiles (id, clinic_id),
    constraint fk_operational_event_patient_same_clinic
        foreign key (patient_id, clinic_id) references patients (id, clinic_id),
    constraint ck_operational_event_type check (event_type in (
        'BOOKED', 'CONFIRMED', 'ARRIVED', 'WAITING', 'CALLED', 'IN_CONSULTATION', 'COMPLETED',
        'CANCELLED', 'SKIPPED', 'REQUEUED', 'NO_SHOW')),
    constraint ck_operational_event_previous check ((event_type = 'BOOKED') = (previous_status is null)),
    constraint ck_operational_event_queue_entry check (
        (event_type in ('WAITING', 'CALLED', 'IN_CONSULTATION', 'COMPLETED', 'SKIPPED', 'REQUEUED'))
            <= (queue_entry_id is not null))
);

-- Analytics read one clinic's events in a time window, optionally for one doctor.
create index idx_operational_events_clinic_time on operational_events (clinic_id, occurred_at);
create index idx_operational_events_doctor_time on operational_events (doctor_id, occurred_at);
create index idx_operational_events_appointment on operational_events (appointment_id, seq);

create function reject_operational_event_change() returns trigger
    language plpgsql as $$
begin
    raise exception 'operational_events is append-only (% rejected)', tg_op
        using errcode = 'restrict_violation';
end;
$$;

-- Row-level, so it covers every UPDATE and DELETE. (TRUNCATE is a separate privilege and
-- statement; it is used only by the test suite to reset the database.)
create trigger operational_events_append_only
    before update or delete on operational_events
    for each row execute function reject_operational_event_change();
