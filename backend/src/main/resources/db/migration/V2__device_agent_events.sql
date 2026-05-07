CREATE TABLE IF NOT EXISTS device_task_events (
    event_id TEXT PRIMARY KEY,
    task_id TEXT NOT NULL,
    user_id TEXT,
    device_id TEXT,
    event_type TEXT NOT NULL,
    status TEXT,
    level TEXT NOT NULL DEFAULT 'info',
    message TEXT,
    output TEXT,
    recoverable BOOLEAN NOT NULL DEFAULT FALSE,
    payload JSONB NOT NULL DEFAULT '{}'::jsonb,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX IF NOT EXISTS idx_device_task_events_task_id ON device_task_events(task_id);
CREATE INDEX IF NOT EXISTS idx_device_task_events_device_id ON device_task_events(device_id);
CREATE INDEX IF NOT EXISTS idx_device_task_events_created_at ON device_task_events(created_at DESC);
CREATE INDEX IF NOT EXISTS idx_device_task_events_payload ON device_task_events USING GIN(payload);
