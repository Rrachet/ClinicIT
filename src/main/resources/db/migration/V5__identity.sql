-- Phase 3: staff accounts and login sessions.

create table users (
    id uuid primary key,
    clinic_id uuid not null references clinics(id),
    email varchar(254) not null,
    password_hash varchar(100) not null,
    full_name varchar(150) not null,
    role varchar(32) not null,
    doctor_profile_id uuid,
    enabled boolean not null,
    created_at timestamp with time zone not null,
    updated_at timestamp with time zone not null,

    -- Login is by email alone, so emails are unique across all clinics. Stored lower-case.
    constraint uk_user_email unique (email),
    constraint ck_user_email_lower_case check (email = lower(email)),
    constraint ck_user_role check (role in ('ADMIN', 'RECEPTIONIST', 'DOCTOR')),

    -- A DOCTOR account is linked to exactly one doctor profile of the same clinic;
    -- other roles are never linked to one.
    constraint ck_user_doctor_link check ((role = 'DOCTOR') = (doctor_profile_id is not null)),
    constraint uk_user_doctor_profile unique (doctor_profile_id),
    constraint fk_user_doctor_same_clinic
        foreign key (doctor_profile_id, clinic_id) references doctor_profiles (id, clinic_id)
);

create index idx_user_clinic on users (clinic_id);

-- Opaque bearer tokens. Only a SHA-256 hash of the token is stored, so a database
-- leak does not hand out working tokens. Rows are revoked on logout / user disable.
create table auth_sessions (
    id uuid primary key,
    user_id uuid not null references users(id),
    token_hash varchar(64) not null,
    created_at timestamp with time zone not null,
    expires_at timestamp with time zone not null,
    revoked_at timestamp with time zone,

    constraint uk_auth_session_token unique (token_hash),
    constraint ck_auth_session_expiry check (expires_at > created_at)
);

create index idx_auth_session_user on auth_sessions (user_id);
