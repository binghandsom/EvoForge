CREATE TABLE IF NOT EXISTS evo_tasks (
    task_id TEXT PRIMARY KEY,
    user_id TEXT,
    device_id TEXT,
    task_type TEXT NOT NULL DEFAULT '',
    source TEXT NOT NULL DEFAULT 'device',
    channel TEXT NOT NULL DEFAULT 'device',
    correlation_id TEXT NOT NULL DEFAULT '',
    route TEXT NOT NULL DEFAULT '',
    capability TEXT NOT NULL DEFAULT '',
    schema_version TEXT NOT NULL DEFAULT '1',
    status TEXT NOT NULL DEFAULT '',
    level TEXT NOT NULL DEFAULT 'info',
    title TEXT NOT NULL DEFAULT '',
    command_text TEXT NOT NULL DEFAULT '',
    recoverable BOOLEAN NOT NULL DEFAULT FALSE,
    request_payload JSONB NOT NULL DEFAULT '{}'::jsonb,
    result_payload JSONB NOT NULL DEFAULT '{}'::jsonb,
    error_payload JSONB NOT NULL DEFAULT '{}'::jsonb,
    event_count INTEGER NOT NULL DEFAULT 0,
    first_event_at TIMESTAMPTZ,
    last_event_at TIMESTAMPTZ,
    started_at TIMESTAMPTZ,
    completed_at TIMESTAMPTZ,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX IF NOT EXISTS idx_evo_tasks_status
    ON evo_tasks(status);

CREATE INDEX IF NOT EXISTS idx_evo_tasks_type
    ON evo_tasks(task_type);

CREATE INDEX IF NOT EXISTS idx_evo_tasks_correlation
    ON evo_tasks(correlation_id);

CREATE INDEX IF NOT EXISTS idx_evo_tasks_route
    ON evo_tasks(route);

CREATE INDEX IF NOT EXISTS idx_evo_tasks_last_event
    ON evo_tasks(last_event_at DESC);

CREATE INDEX IF NOT EXISTS idx_evo_tasks_request_payload
    ON evo_tasks USING GIN(request_payload);

CREATE INDEX IF NOT EXISTS idx_evo_tasks_result_payload
    ON evo_tasks USING GIN(result_payload);

CREATE TABLE IF NOT EXISTS evo_task_checkpoints (
    checkpoint_id TEXT PRIMARY KEY,
    task_id TEXT NOT NULL REFERENCES evo_tasks(task_id) ON DELETE CASCADE,
    checkpoint_key TEXT NOT NULL,
    status TEXT NOT NULL DEFAULT '',
    payload JSONB NOT NULL DEFAULT '{}'::jsonb,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT uq_evo_task_checkpoint UNIQUE (task_id, checkpoint_key)
);

CREATE INDEX IF NOT EXISTS idx_evo_task_checkpoints_task
    ON evo_task_checkpoints(task_id);

CREATE TABLE IF NOT EXISTS evo_task_artifacts (
    artifact_id TEXT PRIMARY KEY,
    task_id TEXT NOT NULL REFERENCES evo_tasks(task_id) ON DELETE CASCADE,
    artifact_type TEXT NOT NULL DEFAULT '',
    name TEXT NOT NULL DEFAULT '',
    uri TEXT NOT NULL DEFAULT '',
    mime_type TEXT NOT NULL DEFAULT '',
    size_bytes BIGINT,
    metadata JSONB NOT NULL DEFAULT '{}'::jsonb,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX IF NOT EXISTS idx_evo_task_artifacts_task
    ON evo_task_artifacts(task_id);

CREATE INDEX IF NOT EXISTS idx_evo_task_artifacts_metadata
    ON evo_task_artifacts USING GIN(metadata);
