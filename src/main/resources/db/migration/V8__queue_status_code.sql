-- Phase 5: patient-facing queue status.
--
-- Each queue entry gets an unguessable code (128 random bits) that the front desk
-- hands to the patient as a link / QR code. The code is a read-only capability for
-- that one entry's position in the queue: no login, no patient data, and it stops
-- working once the queue day has passed. It is never an authentication token.
alter table queue_entries add column status_code varchar(32);

update queue_entries
set status_code = replace(gen_random_uuid()::text, '-', '')
where status_code is null;

alter table queue_entries alter column status_code set not null;

alter table queue_entries add constraint uk_queue_status_code unique (status_code);
