-- Tenant integrity: rows that reference each other must belong to the same clinic.
--
-- Single-column foreign keys only prove the referenced row exists. These composite
-- keys also prove it is in the same clinic, so no code path (including a manual SQL
-- fix) can attach Clinic B's patient to Clinic A's appointment or queue.

alter table patients
    add constraint uk_patient_id_clinic unique (id, clinic_id);

alter table doctor_profiles
    add constraint uk_doctor_id_clinic unique (id, clinic_id);

alter table appointments
    add constraint uk_appointment_id_clinic_doctor unique (id, clinic_id, doctor_id);

alter table appointments
    add constraint fk_appointment_patient_same_clinic
    foreign key (patient_id, clinic_id) references patients (id, clinic_id);

alter table appointments
    add constraint fk_appointment_doctor_same_clinic
    foreign key (doctor_id, clinic_id) references doctor_profiles (id, clinic_id);

-- A queue entry's clinic and doctor must be exactly those of its appointment.
alter table queue_entries
    add constraint fk_queue_appointment_same_clinic_doctor
    foreign key (appointment_id, clinic_id, doctor_id) references appointments (id, clinic_id, doctor_id);
