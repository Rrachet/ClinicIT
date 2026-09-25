create table patients (
    id uuid primary key,
    clinic_id uuid not null,
    full_name varchar(150) not null,
    phone varchar(30) not null,
    date_of_birth date,
    created_at timestamp with time zone not null,
    updated_at timestamp with time zone not null
);

create index idx_patient_clinic_phone on patients (clinic_id, phone);
create index idx_patient_clinic_name on patients (clinic_id, full_name);

create table appointments (
    id uuid primary key,
    clinic_id uuid not null,
    patient_id uuid not null,
    doctor_id uuid not null,
    scheduled_at timestamp not null,
    status varchar(32) not null,
    reason_summary varchar(500),
    created_at timestamp with time zone not null,
    updated_at timestamp with time zone not null
);

create index idx_appointment_clinic_schedule
    on appointments (clinic_id, scheduled_at);

create index idx_appointment_doctor_schedule
    on appointments (doctor_id, scheduled_at);

create table queue_entries (
    id uuid primary key,
    appointment_id uuid not null unique,
    clinic_id uuid not null,
    queue_date date not null,
    token_number integer not null,
    status varchar(32) not null,
    checked_in_at timestamp with time zone,
    called_at timestamp with time zone,
    consultation_started_at timestamp with time zone,
    completed_at timestamp with time zone,
    created_at timestamp with time zone not null,
    constraint uk_queue_clinic_date_token
        unique (clinic_id, queue_date, token_number)
);

create index idx_queue_clinic_date_status
    on queue_entries (clinic_id, queue_date, status);
