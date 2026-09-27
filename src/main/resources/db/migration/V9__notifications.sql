-- Phase 6: patient notifications (SMS / WhatsApp / email) behind a provider abstraction.
--
-- A row is the durable intent to send one message. It is written in the same transaction
-- as the clinic/queue change that caused it (so it exists if and only if that change
-- committed) and is delivered after commit; failures are retried with backoff and never
-- affect the queue operation. Bodies are built from a fixed template and never contain
-- clinical information (see docs/NOTIFICATIONS.md).

alter table appointments
    add constraint uk_appointment_id_clinic unique (id, clinic_id);

create table notifications (
    id uuid primary key,
    clinic_id uuid not null,
    appointment_id uuid not null,
    queue_entry_id uuid references queue_entries(id),
    type varchar(40) not null,
    channel varchar(16) not null,
    recipient varchar(254) not null,
    body varchar(640) not null,
    status varchar(16) not null,
    -- One row per meaningful change: duplicate events can never create a second message.
    dedupe_key varchar(200) not null,
    attempts integer not null default 0,
    max_attempts integer not null,
    next_attempt_at timestamp with time zone not null,
    -- A message that is no longer true (e.g. "please go in now" an hour later) is never sent.
    expires_at timestamp with time zone not null,
    last_error varchar(300),
    provider varchar(40),
    provider_message_id varchar(120),
    created_at timestamp with time zone not null,
    sent_at timestamp with time zone,
    updated_at timestamp with time zone not null,

    constraint uk_notification_dedupe unique (dedupe_key),
    constraint fk_notification_appointment_same_clinic
        foreign key (appointment_id, clinic_id) references appointments (id, clinic_id),
    constraint ck_notification_type check (type in (
        'APPOINTMENT_CONFIRMED', 'PATIENT_JOINED_QUEUE', 'PATIENT_CALLED', 'PATIENT_NEAR_TURN')),
    constraint ck_notification_channel check (channel in ('SMS', 'WHATSAPP', 'EMAIL')),
    constraint ck_notification_status check (status in ('PENDING', 'SENT', 'FAILED')),
    constraint ck_notification_attempts check (attempts >= 0 and attempts <= max_attempts),
    constraint ck_notification_sent check ((status = 'SENT') = (sent_at is not null))
);

-- The retry poller scans only what is due.
create index idx_notification_due on notifications (next_attempt_at) where status = 'PENDING';
create index idx_notification_clinic_appointment on notifications (clinic_id, appointment_id);
create index idx_notification_created on notifications (created_at);
