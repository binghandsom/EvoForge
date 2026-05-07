package com.evoforge.agent

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.dao.EmptyResultDataAccessException
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.core.RowMapper
import org.springframework.stereotype.Component

import java.sql.Array
import java.sql.ResultSet
import java.sql.Timestamp
import java.time.Instant

@Component
@ConditionalOnProperty(prefix = 'evoforge.skills', name = 'storageBackend', havingValue = 'postgres', matchIfMissing = true)
class PostgresAgentKnowledgeStore implements AgentKnowledgeStore {
    private final JdbcTemplate jdbcTemplate

    PostgresAgentKnowledgeStore(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate
    }

    @Override
    List<AgentKnowledgeFact> loadAll() {
        return jdbcTemplate.query('''
            SELECT id, fact_key, fact_value, scope, tags, source, confidence, created_at, updated_at
            FROM agent_knowledge_facts
            ORDER BY updated_at DESC
        ''', mapper())
    }

    @Override
    Optional<AgentKnowledgeFact> findByKey(String key, String scope) {
        try {
            AgentKnowledgeFact fact = jdbcTemplate.queryForObject('''
                SELECT id, fact_key, fact_value, scope, tags, source, confidence, created_at, updated_at
                FROM agent_knowledge_facts
                WHERE fact_key = ? AND scope = ?
            ''', mapper(), key, scope ?: 'global')
            return Optional.ofNullable(fact)
        } catch (EmptyResultDataAccessException ignored) {
            return Optional.empty()
        }
    }

    @Override
    AgentKnowledgeFact save(AgentKnowledgeFact fact) {
        fact.id = fact.id ?: UUID.randomUUID().toString()
        fact.createdAt = fact.createdAt ?: Instant.now()
        fact.updatedAt = fact.updatedAt ?: Instant.now()
        jdbcTemplate.update('''
            INSERT INTO agent_knowledge_facts (
                id, fact_key, fact_value, scope, tags, source, confidence, created_at, updated_at
            )
            VALUES (?, ?, ?, ?, ?::text[], ?, ?, ?, ?)
            ON CONFLICT (scope, fact_key) DO UPDATE SET
                fact_value = EXCLUDED.fact_value,
                tags = EXCLUDED.tags,
                source = EXCLUDED.source,
                confidence = EXCLUDED.confidence,
                updated_at = EXCLUDED.updated_at
        ''',
            fact.id,
            fact.key,
            fact.value,
            fact.scope ?: 'global',
            toPostgresTextArray(fact.tags ?: []),
            fact.source ?: 'agent',
            fact.confidence,
            Timestamp.from(fact.createdAt),
            Timestamp.from(fact.updatedAt)
        )
        return findByKey(fact.key, fact.scope).orElse(fact)
    }

    @Override
    void delete(String id) {
        jdbcTemplate.update('DELETE FROM agent_knowledge_facts WHERE id = ?', id)
    }

    private RowMapper<AgentKnowledgeFact> mapper() {
        return { ResultSet rs, int rowNum -> mapFact(rs) } as RowMapper<AgentKnowledgeFact>
    }

    private static AgentKnowledgeFact mapFact(ResultSet rs) {
        Array tagArray = rs.getArray('tags')
        return new AgentKnowledgeFact(
            id: rs.getString('id'),
            key: rs.getString('fact_key'),
            value: rs.getString('fact_value'),
            scope: rs.getString('scope'),
            tags: tagArray ? ((Object[]) tagArray.array).collect { it.toString() } : [],
            source: rs.getString('source'),
            confidence: rs.getDouble('confidence'),
            createdAt: readInstant(rs, 'created_at'),
            updatedAt: readInstant(rs, 'updated_at')
        )
    }

    private static String toPostgresTextArray(Collection<String> values) {
        String body = (values ?: []).collect { value ->
            '"' + value.replace('\\', '\\\\').replace('"', '\\"') + '"'
        }.join(',')
        return "{${body}}"
    }

    private static Instant readInstant(ResultSet rs, String column) {
        Timestamp timestamp = rs.getTimestamp(column)
        return timestamp ? timestamp.toInstant() : null
    }
}
