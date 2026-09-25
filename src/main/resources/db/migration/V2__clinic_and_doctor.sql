create table clinics (
    id uuid primary key,
    name varchar(150) not null,
    address varchar(500),
    timezone varchar(64) not null
);

create table doctor_profiles (
    id uuid primary key,
    clinic_id uuid not null references clinics(id),
    display_name varchar(150) not null,
    specialization varchar(120),
    created_at timestamp with time zone not null
);

create index idx_doctor_clinic_name
    on doctor_profiles (clinic_id, display_name);

alter table patients
    add constraint fk_patient_clinic
    foreign key (clinic_id) references clinics(id);

alter table appointments
    add constraint fk_appointment_clinic
    foreign key (clinic_id) references clinics(id);

alter table appointments
    add constraint fk_appointment_patient
    foreign key (patient_id) references patients(id);

alter table appointments
    add constraint fk_appointment_doctor
    foreign key (doctor_id) references doctor_profiles(id);

alter table queue_entries
    add constraint fk_queue_appointment
    foreign key (appointment_id) references appointments(id);

alter table queue_entries
    add constraint fk_queue_clinic
    foreign key (clinic_id) references clinics(id);
