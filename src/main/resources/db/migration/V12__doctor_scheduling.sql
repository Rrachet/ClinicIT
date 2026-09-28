-- Phase 10: doctor scheduling.
--
-- Time model (docs/SCHEDULING.md): everything a clinic schedules is clinic-local wall-clock
-- time, like appointments.scheduled_at. Weekly hours are a day of week plus local times;
-- time off is a local date-time range. Instants (timestamptz) stay for things that happened.

-- Length of one appointment slot. Used for overlaps and for listing free slots.
alter table doctor_profiles
    add column appointment_minutes integer not null default 15,
    add constraint ck_doctor_appointment_minutes check (appointment_minutes between 5 and 120);

-- One row per working day; a day without a row is a day off. At most one break per day
-- (lunch). Deliberately no recurrence rules beyond "every week".
create table doctor_working_hours (
    clinic_id   uuid     not null,
    doctor_id   uuid     not null,
    day_of_week smallint not null,          -- ISO: 1 = Monday ... 7 = Sunday
    start_time  time     not null,
    end_time    time     not null,
    break_start time,
    break_end   time,

    constraint pk_doctor_working_hours primary key (doctor_id, day_of_week),
    constraint fk_working_hours_doctor_same_clinic
        foreign key (doctor_id, clinic_id) references doctor_profiles (id, clinic_id) on delete cascade,
    constraint ck_working_hours_day check (day_of_week between 1 and 7),
    constraint ck_working_hours_order check (start_time < end_time),
    constraint ck_working_hours_break check (
        (break_start is null and break_end is null)
        or (break_start is not null and break_end is not null
            and start_time < break_start and break_start < break_end and break_end < end_time))
);

-- Leave and other unavailable periods, e.g. a conference day or an afternoon off.
create table doctor_time_off (
    id         uuid primary key default gen_random_uuid(),
    clinic_id  uuid         not null,
    doctor_id  uuid         not null,
    starts_at  timestamp without time zone not null,
    ends_at    timestamp without time zone not null,
    -- Shown to staff only, e.g. "Conference". Never shown to patients.
    reason     varchar(200),
    created_at timestamp with time zone not null default now(),

    constraint fk_time_off_doctor_same_clinic
        foreign key (doctor_id, clinic_id) references doctor_profiles (id, clinic_id) on delete cascade,
    constraint ck_time_off_order check (starts_at < ends_at)
);
create index idx_doctor_time_off_doctor on doctor_time_off (doctor_id, starts_at);

-- A walk-in is booked for "now" and checked in at once. Walk-ins join the queue in arrival
-- order and never hold a slot, so a full day of bookings cannot turn a walk-in away.
alter table appointments add column walk_in boolean not null default false;

-- Slot checks read one doctor's appointments for one day: idx_appointment_doctor_schedule
-- (V1) already covers them.

-- History of a changed appointment time (the status does not change).
alter table operational_events drop constraint ck_operational_event_type;
alter table operational_events add constraint ck_operational_event_type check (event_type in (
    'BOOKED', 'CONFIRMED', 'ARRIVED', 'WAITING', 'CALLED', 'IN_CONSULTATION', 'COMPLETED',
    'CANCELLED', 'SKIPPED', 'REQUEUED', 'NO_SHOW', 'RESCHEDULED'));
