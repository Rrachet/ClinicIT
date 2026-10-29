-- AI receptionist chats (docs/AI_RECEPTIONIST.md). One row per conversation a patient starts
-- from the clinic's QR code. messages holds the Claude API transcript exactly as sent and
-- received (tool calls and results included), so the next turn can replay it unchanged.
-- Patients type their name and phone here, so rows are deleted after a day.
create table receptionist_conversations (
    id           uuid primary key default gen_random_uuid(),
    clinic_id    uuid        not null references clinics (id),
    messages     text        not null,
    user_turns   integer     not null default 0,
    version      bigint      not null default 0,
    created_at   timestamptz not null,
    updated_at   timestamptz not null
);

create index ix_receptionist_conversations_updated on receptionist_conversations (updated_at);
