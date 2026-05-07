package com.evoforge.history

import com.evoforge.core.JsonColumns
import com.evoforge.model.SkillStatus
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
class PostgresSkillHistoryStore implements SkillHistoryStore {
    private final JdbcTemplate jdbcTemplate
    private final ObjectMapper objectMapper

    PostgresSkillHistoryStore(JdbcTemplate jdbcTemplate, ObjectMapper objectMapper) {
        this.jdbcTemplate = jdbcTemplate
        this.objectMapper = objectMapper
    }

    @Override
    List<SkillHistoryEntry> loadAll() {
        return jdbcTemplate.query('''
            SELECT id, skill_id, name, version, language, entry_class, code, status, metadata::text AS metadata, created_at
            FROM skill_versions
            ORDER BY created_at DESC
        ''', historyMapper())
    }

    @Override
    void append(SkillHistoryEntry entry) {
        Instant createdAt = entry.createdAt ?: Instant.now()
        entry.createdAt = createdAt
        jdbcTemplate.update('''
            INSERT INTO skill_versions (
                id, skill_id, name, version, language, entry_class, code, status, metadata, created_at
            )
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?::jsonb, ?)
        ''',
            entry.id,
            entry.skillId,
            entry.name,
            entry.version,
            entry.language ?: 'groovy',
            entry.entryClass,
            entry.code,
            entry.status?.name(),
            JsonColumns.writeMap(objectMapper, entry.metadata),
            Timestamp.from(createdAt)
        )
    }

    @Override
    List<SkillHistoryEntry> listForSkill(String skillId) {
        return jdbcTemplate.query('''
            SELECT id, skill_id, name, version, language, entry_class, code, status, metadata::text AS metadata, created_at
            FROM skill_versions
            WHERE skill_id = ?
            ORDER BY created_at DESC
        ''', historyMapper(), skillId)
    }

    @Override
    SkillHistoryEntry findById(String id) {
        try {
            return jdbcTemplate.queryForObject('''
                SELECT id, skill_id, name, version, language, entry_class, code, status, metadata::text AS metadata, created_at
                FROM skill_versions
                WHERE id = ?
            ''', historyMapper(), id)
        } catch (EmptyResultDataAccessException ignored) {
            return null
        }
    }

    private RowMapper<SkillHistoryEntry> historyMapper() {
        return { ResultSet rs, int rowNum -> mapHistory(rs) } as RowMapper<SkillHistoryEntry>
    }

    private SkillHistoryEntry mapHistory(ResultSet rs) {
        String status = rs.getString('status')
        return new SkillHistoryEntry(
            id: rs.getString('id'),
            skillId: rs.getString('skill_id'),
            name: rs.getString('name'),
            version: rs.getString('version'),
            language: rs.getString('language'),
            entryClass: rs.getString('entry_class'),
            code: rs.getString('code'),
            status: status ? SkillStatus.valueOf(status) : null,
            metadata: JsonColumns.readMap(objectMapper, rs.getString('metadata')),
            createdAt: readInstant(rs, 'created_at')
        )
    }

    private static Instant readInstant(ResultSet rs, String column) {
        Timestamp timestamp = rs.getTimestamp(column)
        return timestamp ? timestamp.toInstant() : null
    }
}
