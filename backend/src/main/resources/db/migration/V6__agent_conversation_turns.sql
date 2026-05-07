CREATE TABLE IF NOT EXISTS agent_conversation_turns (
    id TEXT PRIMARY KEY,
    thread_id TEXT NOT NULL,
    role TEXT NOT NULL,
    content TEXT NOT NULL,
    metadata JSONB NOT NULL DEFAULT '{}'::JSONB,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX IF NOT EXISTS idx_agent_conversation_thread_created_at
    ON agent_conversation_turns(thread_id, created_at DESC);

CREATE INDEX IF NOT EXISTS idx_agent_conversation_role
    ON agent_conversation_turns(role);
