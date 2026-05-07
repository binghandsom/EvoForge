CREATE TABLE IF NOT EXISTS agent_knowledge_facts (
    id TEXT PRIMARY KEY,
    fact_key TEXT NOT NULL,
    fact_value TEXT NOT NULL,
    scope TEXT NOT NULL DEFAULT 'global',
    tags TEXT[] NOT NULL DEFAULT ARRAY[]::TEXT[],
    source TEXT NOT NULL DEFAULT 'agent',
    confidence DOUBLE PRECISION NOT NULL DEFAULT 0.7,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT uq_agent_knowledge_scope_key UNIQUE (scope, fact_key)
);

CREATE INDEX IF NOT EXISTS idx_agent_knowledge_scope ON agent_knowledge_facts(scope);
CREATE INDEX IF NOT EXISTS idx_agent_knowledge_tags ON agent_knowledge_facts USING GIN(tags);
CREATE INDEX IF NOT EXISTS idx_agent_knowledge_updated_at ON agent_knowledge_facts(updated_at DESC);
