CREATE TABLE IF NOT EXISTS skills (
    id TEXT PRIMARY KEY,
    name TEXT NOT NULL,
    version TEXT NOT NULL,
    language TEXT NOT NULL DEFAULT 'groovy',
    entry_class TEXT,
    code TEXT NOT NULL,
    enabled BOOLEAN NOT NULL DEFAULT FALSE,
    status TEXT NOT NULL DEFAULT 'DRAFT',
    checksum TEXT,
    metadata JSONB NOT NULL DEFAULT '{}'::jsonb,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX IF NOT EXISTS idx_skills_enabled ON skills(enabled);
CREATE INDEX IF NOT EXISTS idx_skills_status ON skills(status);
CREATE INDEX IF NOT EXISTS idx_skills_metadata ON skills USING GIN(metadata);

CREATE TABLE IF NOT EXISTS skill_versions (
    id TEXT PRIMARY KEY,
    skill_id TEXT NOT NULL,
    name TEXT NOT NULL,
    version TEXT NOT NULL,
    language TEXT NOT NULL DEFAULT 'groovy',
    entry_class TEXT,
    code TEXT NOT NULL,
    status TEXT,
    metadata JSONB NOT NULL DEFAULT '{}'::jsonb,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX IF NOT EXISTS idx_skill_versions_skill_id ON skill_versions(skill_id);
CREATE INDEX IF NOT EXISTS idx_skill_versions_created_at ON skill_versions(created_at DESC);

CREATE TABLE IF NOT EXISTS skill_audit_events (
    id TEXT PRIMARY KEY,
    type TEXT NOT NULL,
    skill_id TEXT,
    skill_name TEXT,
    occurred_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    payload JSONB NOT NULL DEFAULT '{}'::jsonb
);

CREATE INDEX IF NOT EXISTS idx_skill_audit_events_skill_id ON skill_audit_events(skill_id);
CREATE INDEX IF NOT EXISTS idx_skill_audit_events_occurred_at ON skill_audit_events(occurred_at DESC);
CREATE INDEX IF NOT EXISTS idx_skill_audit_events_payload ON skill_audit_events USING GIN(payload);

CREATE TABLE IF NOT EXISTS skill_git_refs (
    skill_id TEXT PRIMARY KEY REFERENCES skills(id) ON DELETE CASCADE,
    repo_path TEXT NOT NULL,
    manifest_path TEXT,
    groovy_path TEXT,
    commit_hash TEXT,
    synced_checksum TEXT,
    synced_at TIMESTAMPTZ
);
