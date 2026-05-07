CREATE TABLE IF NOT EXISTS device_command_replay (
    command_id TEXT PRIMARY KEY,
    seen_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX IF NOT EXISTS idx_device_command_replay_seen_at ON device_command_replay(seen_at);
