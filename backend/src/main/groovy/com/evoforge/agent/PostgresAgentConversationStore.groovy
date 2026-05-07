package com.evoforge.agent

import com.evoforge.core.JsonColumns
import com.fasterxml.jackson.databind.ObjectMapper
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.dao.EmptyResultDataAccessException
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.core.RowMapper
import org.springframework.stereotype.Component

import java.sql.ResultSet
import java.sql.Timestamp
import java.time.Instant

@Component
@ConditionalOnProperty(prefix = 'evoforge.skills', name = 'storageBackend', havingValue = 'postgres', matchIfMissing = true)
class PostgresAgentConversationStore implements AgentConversationStore {
    private final JdbcTemplate jdbcTemplate
    private final ObjectMapper objectMapper

    PostgresAgentConversationStore(JdbcTemplate jdbcTemplate, ObjectMapper objectMapper) {
        this.jdbcTemplate = jdbcTemplate
        this.objectMapper = objectMapper
    }

    @Override
    AgentConversationThread upsertThread(AgentConversationThread thread) {
        thread.threadId = thread.threadId ?: UUID.randomUUID().toString()
        thread.createdAt = thread.createdAt ?: Instant.now()
        thread.updatedAt = thread.updatedAt ?: Instant.now()
        jdbcTemplate.update('''
            INSERT INTO agent_conversation_threads (
                thread_id, title, summary, metadata, turn_count, last_role, last_content, created_at, updated_at
            )
            VALUES (?, ?, ?, ?::jsonb, ?, ?, ?, ?, ?)
            ON CONFLICT (thread_id) DO UPDATE SET
                title = EXCLUDED.title,
                summary = EXCLUDED.summary,
                metadata = agent_conversation_threads.metadata || EXCLUDED.metadata,
                updated_at = EXCLUDED.updated_at
        ''',
            thread.threadId,
            thread.title ?: '新对话',
            thread.summary ?: '',
            JsonColumns.writeMap(objectMapper, thread.metadata ?: [:]),
            thread.turnCount,
            thread.lastRole ?: '',
            thread.lastContent ?: '',
            Timestamp.from(thread.createdAt),
            Timestamp.from(thread.updatedAt)
        )
        return findThread(thread.threadId).orElse(thread)
    }

    @Override
    Optional<AgentConversationThread> findThread(String threadId) {
        try {
            AgentConversationThread thread = jdbcTemplate.queryForObject('''
                SELECT thread_id, title, summary, metadata::text AS metadata, turn_count, last_role, last_content, created_at, updated_at
                FROM agent_conversation_threads
                WHERE thread_id = ?
            ''', threadMapper(), threadId)
            return Optional.ofNullable(thread)
        } catch (EmptyResultDataAccessException ignored) {
            return Optional.empty()
        }
    }

    @Override
    List<AgentConversationThread> listThreads(int limit) {
        int safeLimit = Math.max(1, Math.min(limit, 200))
        return jdbcTemplate.query('''
            SELECT thread_id, title, summary, metadata::text AS metadata, turn_count, last_role, last_content, created_at, updated_at
            FROM agent_conversation_threads
            ORDER BY updated_at DESC
            LIMIT ?
        ''', threadMapper(), safeLimit)
    }

    @Override
    void touchThread(String threadId, String role, String content, Instant updatedAt, String titleCandidate) {
        jdbcTemplate.update('''
            UPDATE agent_conversation_threads
            SET
                title = CASE
                    WHEN (title IS NULL OR title = '' OR title = '新对话') AND ? <> '' THEN ?
                    ELSE title
                END,
                turn_count = turn_count + 1,
                last_role = ?,
                last_content = ?,
                updated_at = ?
            WHERE thread_id = ?
        ''',
            titleCandidate ?: '',
            titleCandidate ?: '',
            role ?: '',
            compact(content ?: '', 400),
            Timestamp.from(updatedAt ?: Instant.now()),
            threadId
        )
    }

    @Override
    AgentConversationTurn append(AgentConversationTurn turn) {
        turn.id = turn.id ?: UUID.randomUUID().toString()
        turn.createdAt = turn.createdAt ?: Instant.now()
        jdbcTemplate.update('''
            INSERT INTO agent_conversation_turns (
                id, thread_id, role, content, metadata, created_at
            )
            VALUES (?, ?, ?, ?, ?::jsonb, ?)
        ''',
            turn.id,
            turn.threadId,
            turn.role,
            turn.content ?: '',
            JsonColumns.writeMap(objectMapper, turn.metadata ?: [:]),
            Timestamp.from(turn.createdAt)
        )
        return turn
    }

    @Override
    List<AgentConversationTurn> listRecent(String threadId, int limit) {
        int safeLimit = Math.max(1, Math.min(limit, 100))
        List<AgentConversationTurn> newestFirst = jdbcTemplate.query('''
            SELECT id, thread_id, role, content, metadata::text AS metadata, created_at
            FROM agent_conversation_turns
            WHERE thread_id = ?
            ORDER BY created_at DESC
            LIMIT ?
        ''', mapper(), threadId, safeLimit)
        return newestFirst.reverse()
    }

    private RowMapper<AgentConversationTurn> mapper() {
        return { ResultSet rs, int rowNum -> mapTurn(rs) } as RowMapper<AgentConversationTurn>
    }

    private RowMapper<AgentConversationThread> threadMapper() {
        return { ResultSet rs, int rowNum -> mapThread(rs) } as RowMapper<AgentConversationThread>
    }

    private AgentConversationThread mapThread(ResultSet rs) {
        return new AgentConversationThread(
            threadId: rs.getString('thread_id'),
            title: rs.getString('title'),
            summary: rs.getString('summary'),
            metadata: JsonColumns.readMap(objectMapper, rs.getString('metadata')),
            turnCount: rs.getInt('turn_count'),
            lastRole: rs.getString('last_role'),
            lastContent: rs.getString('last_content'),
            createdAt: readInstant(rs, 'created_at'),
            updatedAt: readInstant(rs, 'updated_at')
        )
    }

    private AgentConversationTurn mapTurn(ResultSet rs) {
        return new AgentConversationTurn(
            id: rs.getString('id'),
            threadId: rs.getString('thread_id'),
            role: rs.getString('role'),
            content: rs.getString('content'),
            metadata: JsonColumns.readMap(objectMapper, rs.getString('metadata')),
            createdAt: readInstant(rs, 'created_at')
        )
    }

    private static Instant readInstant(ResultSet rs, String column) {
        Timestamp timestamp = rs.getTimestamp(column)
        return timestamp ? timestamp.toInstant() : null
    }

    private static String compact(String text, int limit) {
        String normalized = (text ?: '').replaceAll('\\s+', ' ').trim()
        return normalized.length() > limit ? normalized.substring(0, limit) + '...' : normalized
    }
}
