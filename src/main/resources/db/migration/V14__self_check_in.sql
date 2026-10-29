-- Self check-in: patients scan the clinic's QR code, pick a visit type and are queued with an
-- on-duty doctor who handles it, with no receptionist (docs/SELF_CHECK_IN.md).

-- A doctor is "live" (seeing patients) for one clinic-local day: on_duty_date is the day they
-- went live, so a doctor who forgets to go offline is not live tomorrow. room is shown to the
-- patient when they are called ("Go to Room 3").
alter table doctor_profiles
    add column on_duty_date  date,
    add column on_duty_since timestamptz,
    add column room          varchar(40);

-- The public code printed in the clinic's check-in QR code. Not the clinic id, so it can be
-- replaced if a poster is misused.
alter table clinics add column check_in_code varchar(32);
update clinics set check_in_code = replace(gen_random_uuid()::text, '-', '') where check_in_code is null;
alter table clinics
    alter column check_in_code set not null,
    add constraint uq_clinics_check_in_code unique (check_in_code);

-- What a patient can come in for, as the clinic names it ("General check-up", "Child
-- (paediatrics)"), and which doctors see it. The patient chooses; nothing is inferred from
-- what they write about their health.
create table visit_types (
    id          uuid primary key default gen_random_uuid(),
    clinic_id   uuid         not null references clinics (id),
    name        varchar(80)  not null,
    description varchar(200),
    any_doctor  boolean      not null default false,
    sort_order  integer      not null default 0,
    active      boolean      not null default true,
    created_at  timestamptz  not null default now(),

    constraint uq_visit_types_id_clinic unique (id, clinic_id),
    constraint uq_visit_types_clinic_name unique (clinic_id, name)
);

create table visit_type_doctors (
    clinic_id     uuid not null,
    visit_type_id uuid not null,
    doctor_id     uuid not null,

    constraint pk_visit_type_doctors primary key (visit_type_id, doctor_id),
    constraint fk_visit_type_doctors_type_same_clinic
        foreign key (visit_type_id, clinic_id) references visit_types (id, clinic_id) on delete cascade,
    constraint fk_visit_type_doctors_doctor_same_clinic
        foreign key (doctor_id, clinic_id) references doctor_profiles (id, clinic_id) on delete cascade
);

create index ix_visit_type_doctors_doctor on visit_type_doctors (doctor_id);

-- The visit type a self check-in chose, kept with the appointment for the doctor and the figures.
alter table appointments add column visit_type_id uuid references visit_types (id);
