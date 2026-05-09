CREATE TABLE IF NOT EXISTS tester_capabilities (
    record_id TEXT PRIMARY KEY,
    project_key TEXT NOT NULL,
    capability_id TEXT NOT NULL,
    name TEXT NOT NULL,
    capability_type TEXT NOT NULL DEFAULT '',
    working_directory TEXT NOT NULL DEFAULT '',
    command TEXT NOT NULL,
    enabled BOOLEAN NOT NULL DEFAULT TRUE,
    timeout_seconds INTEGER NOT NULL DEFAULT 0,
    reason TEXT NOT NULL DEFAULT '',
    covers TEXT[] NOT NULL DEFAULT ARRAY[]::TEXT[],
    tags TEXT[] NOT NULL DEFAULT ARRAY[]::TEXT[],
    cost TEXT NOT NULL DEFAULT '',
    confidence TEXT NOT NULL DEFAULT '',
    evidence_parser TEXT NOT NULL DEFAULT '',
    fallback_command_ids TEXT[] NOT NULL DEFAULT ARRAY[]::TEXT[],
    repair_scopes TEXT[] NOT NULL DEFAULT ARRAY[]::TEXT[],
    source TEXT NOT NULL DEFAULT 'manual',
    optimization_notes TEXT NOT NULL DEFAULT '',
    success_count INTEGER NOT NULL DEFAULT 0,
    failure_count INTEGER NOT NULL DEFAULT 0,
    last_status TEXT NOT NULL DEFAULT '',
    last_exit_code INTEGER,
    last_duration_ms BIGINT,
    last_output_excerpt TEXT NOT NULL DEFAULT '',
    last_run_at TIMESTAMPTZ,
    metadata JSONB NOT NULL DEFAULT '{}'::jsonb,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT uq_tester_capability_project_id UNIQUE (project_key, capability_id)
);

CREATE INDEX IF NOT EXISTS idx_tester_capabilities_project
    ON tester_capabilities(project_key);

CREATE INDEX IF NOT EXISTS idx_tester_capabilities_enabled
    ON tester_capabilities(enabled);

CREATE INDEX IF NOT EXISTS idx_tester_capabilities_tags
    ON tester_capabilities USING GIN(tags);

