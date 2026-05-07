package com.evoforge.store

import com.evoforge.core.JsonColumns
import com.evoforge.model.SkillDefinition
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
class PostgresSkillStore implements SkillStore {
    private final JdbcTemplate jdbcTemplate
    private final ObjectMapper objectMapper

    PostgresSkillStore(JdbcTemplate jdbcTemplate, ObjectMapper objectMapper) {
        this.jdbcTemplate = jdbcTemplate
        this.objectMapper = objectMapper
    }

    @Override
    List<SkillDefinition> loadAll() {
        return jdbcTemplate.query('''
            SELECT id, name, version, language, entry_class, code, enabled, status, checksum, metadata::text AS metadata, created_at, updated_at
            FROM skills
            ORDER BY created_at ASC
        ''', skillMapper())
    }

    @Override
    Optional<SkillDefinition> findById(String id) {
        try {
            SkillDefinition skill = jdbcTemplate.queryForObject('''
                SELECT id, name, version, language, entry_class, code, enabled, status, checksum, metadata::text AS metadata, created_at, updated_at
                FROM skills
                WHERE id = ?
            ''', skillMapper(), id)
            return Optional.ofNullable(skill)
        } catch (EmptyResultDataAccessException ignored) {
            return Optional.empty()
        }
    }

    @Override
    SkillDefinition save(SkillDefinition skill) {
        Instant createdAt = skill.createdAt ?: Instant.now()
        Instant updatedAt = skill.updatedAt ?: Instant.now()
        skill.createdAt = createdAt
        skill.updatedAt = updatedAt

        jdbcTemplate.update('''
            INSERT INTO skills (
                id, name, version, language, entry_class, code, enabled, status, checksum, metadata, created_at, updated_at
            )
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?::jsonb, ?, ?)
            ON CONFLICT (id) DO UPDATE SET
                name = EXCLUDED.name,
                version = EXCLUDED.version,
                language = EXCLUDED.language,
                entry_class = EXCLUDED.entry_class,
                code = EXCLUDED.code,
                enabled = EXCLUDED.enabled,
                status = EXCLUDED.status,
                checksum = EXCLUDED.checksum,
                metadata = EXCLUDED.metadata,
                updated_at = EXCLUDED.updated_at
        ''',
            skill.id,
            skill.name,
            skill.version,
            skill.language ?: 'groovy',
            skill.entryClass,
            skill.code,
            skill.enabled,
            (skill.status ?: SkillStatus.DRAFT).name(),
            skill.checksum,
            JsonColumns.writeMap(objectMapper, skill.metadata),
            Timestamp.from(createdAt),
            Timestamp.from(updatedAt)
        )
        return skill
    }

    @Override
    void delete(String id) {
        jdbcTemplate.update('DELETE FROM skills WHERE id = ?', id)
    }

    private RowMapper<SkillDefinition> skillMapper() {
        return { ResultSet rs, int rowNum -> mapSkill(rs) } as RowMapper<SkillDefinition>
    }

    private SkillDefinition mapSkill(ResultSet rs) {
        return new SkillDefinition(
            id: rs.getString('id'),
            name: rs.getString('name'),
            version: rs.getString('version'),
            language: rs.getString('language'),
            entryClass: rs.getString('entry_class'),
            code: rs.getString('code'),
            enabled: rs.getBoolean('enabled'),
            status: SkillStatus.valueOf(rs.getString('status')),
            checksum: rs.getString('checksum'),
            metadata: JsonColumns.readMap(objectMapper, rs.getString('metadata')),
            createdAt: readInstant(rs, 'created_at'),
            updatedAt: readInstant(rs, 'updated_at')
        )
    }

    private static Instant readInstant(ResultSet rs, String column) {
        Timestamp timestamp = rs.getTimestamp(column)
        return timestamp ? timestamp.toInstant() : null
    }
}
