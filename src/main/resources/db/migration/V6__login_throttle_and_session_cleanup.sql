-- Brute-force protection for login. One row per throttled key, where the key is the
-- SHA-256 of "account:<email>" or "ip:<address>" (so typed emails/IPs are not stored
-- in clear). Counters use a fixed window that restarts once it has elapsed.
create table login_throttle (
    throttle_key varchar(64) primary key,
    attempts integer not null check (attempts > 0),
    window_started_at timestamp with time zone not null
);

create index idx_login_throttle_window on login_throttle (window_started_at);

-- Supports the periodic purge of expired sessions.
create index idx_auth_session_expires on auth_sessions (expires_at);
