-- Phase 9 index review (docs/DATA_MODEL.md, "Indexes"). Every query was checked with
-- EXPLAIN ANALYZE against 60 simulated clinic days; all online queries already use an index.
--
-- These two serve no query and only add write cost:
--  * patients are searched by name only (the lookup by phone was removed as dead code);
--  * notifications are listed per appointment (idx_notification_clinic_appointment), never by
--    creation time.
-- A future feature that needs either brings back the index it needs.
drop index if exists idx_patient_clinic_phone;
drop index if exists idx_notification_created;
