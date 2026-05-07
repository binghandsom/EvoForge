CREATE TABLE IF NOT EXISTS agent_conversation_threads (
    thread_id TEXT PRIMARY KEY,
    title TEXT NOT NULL DEFAULT '新对话',
    summary TEXT NOT NULL DEFAULT '',
    metadata JSONB NOT NULL DEFAULT '{}'::JSONB,
    turn_count INTEGER NOT NULL DEFAULT 0,
    last_role TEXT NOT NULL DEFAULT '',
    last_content TEXT NOT NULL DEFAULT '',
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX IF NOT EXISTS idx_agent_conversation_threads_updated_at
    ON agent_conversation_threads(updated_at DESC);

INSERT INTO agent_conversation_threads (
    thread_id, title, turn_count, last_role, last_content, created_at, updated_at
)
SELECT
    t.thread_id,
    COALESCE(NULLIF(LEFT(regexp_replace(first_user.content, '\s+', ' ', 'g'), 42), ''), '新对话') AS title,
    COUNT(*)::INTEGER AS turn_count,
    COALESCE((ARRAY_AGG(t.role ORDER BY t.created_at DESC))[1], '') AS last_role,
    COALESCE(LEFT(regexp_replace((ARRAY_AGG(t.content ORDER BY t.created_at DESC))[1], '\s+', ' ', 'g'), 400), '') AS last_content,
    MIN(t.created_at) AS created_at,
    MAX(t.created_at) AS updated_at
FROM agent_conversation_turns t
LEFT JOIN LATERAL (
    SELECT content
    FROM agent_conversation_turns u
    WHERE u.thread_id = t.thread_id AND u.role = 'user'
    ORDER BY u.created_at ASC
    LIMIT 1
) first_user ON TRUE
GROUP BY t.thread_id, first_user.content
ON CONFLICT (thread_id) DO NOTHING;
