package com.evoforge.audit

import com.evoforge.core.JsonColumns
import com.fasterxml.jackson.databind.ObjectMapper
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.core.RowMapper
import org.springframework.stereotype.Component

import java.sql.ResultSet
import java.sql.Timestamp
import java.time.Instant

@Component
@ConditionalOnProperty(prefix = 'evoforge.skills', name = 'storageBackend', havingValue = 'postgres', matchIfMissing = true)
class PostgresSkillAuditStore implements SkillAuditStore {
    private final JdbcTemplate jdbcTemplate
    private final ObjectMapper objectMapper

    PostgresSkillAuditStore(JdbcTemplate jdbcTemplate, ObjectMapper objectMapper) {
        this.jdbcTemplate = jdbcTemplate
        this.objectMapper = objectMapper
    }

    @Override
    List<SkillEvent> loadAll() {
        return jdbcTemplate.query('''
            SELECT id, type, skill_id, skill_name, occurred_at, payload::text AS payload
            FROM skill_audit_events
            ORDER BY occurred_at DESC
        ''', eventMapper())
    }

    @Override
    void append(SkillEvent event) {
        Instant timestamp = event.timestamp ?: Instant.now()
        event.timestamp = timestamp
        jdbcTemplate.update('''
            INSERT INTO skill_audit_events (
                id, type, skill_id, skill_name, occurred_at, payload
            )
            VALUES (?, ?, ?, ?, ?, ?::jsonb)
        ''',
            event.id,
            event.type.name(),
            event.skillId,
            event.skillName,
            Timestamp.from(timestamp),
            JsonColumns.writeMap(objectMapper, event.payload)
        )
    }

    @Override
    List<SkillEvent> listForSkill(String skillId) {
        return jdbcTemplate.query('''
            SELECT id, type, skill_id, skill_name, occurred_at, payload::text AS payload
            FROM skill_audit_events
            WHERE skill_id = ?
            ORDER BY occurred_at DESC
        ''', eventMapper(), skillId)
    }

    @Override
    void clear() {
        jdbcTemplate.update('DELETE FROM skill_audit_events')
    }

    private RowMapper<SkillEvent> eventMapper() {
        return { ResultSet rs, int rowNum -> mapEvent(rs) } as RowMapper<SkillEvent>
    }

    private SkillEvent mapEvent(ResultSet rs) {
        return new SkillEvent(
            id: rs.getString('id'),
            type: SkillEventType.valueOf(rs.getString('type')),
            skillId: rs.getString('skill_id'),
            skillName: rs.getString('skill_name'),
            timestamp: readInstant(rs, 'occurred_at'),
            payload: JsonColumns.readMap(objectMapper, rs.getString('payload'))
        )
    }

    private static Instant readInstant(ResultSet rs, String column) {
        Timestamp timestamp = rs.getTimestamp(column)
        return timestamp ? timestamp.toInstant() : null
    }
}
