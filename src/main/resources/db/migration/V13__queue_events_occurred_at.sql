-- QueueEventRelay reads the outbox rows of the last few seconds on every instance, once a second.
create index idx_queue_events_occurred_at on queue_events (occurred_at);
