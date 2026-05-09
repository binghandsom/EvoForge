package com.evoforge.store

import com.evoforge.core.JsonColumns
import com.evoforge.model.SkillDefinition
import com.evoforge.model.SkillStatus
import com.evoforge.skills.SkillCodeStorage
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
    private final SkillCodeStorage codeStorage

    PostgresSkillStore(JdbcTemplate jdbcTemplate, ObjectMapper objectMapper, SkillCodeStorage codeStorage) {
        this.jdbcTemplate = jdbcTemplate
        this.objectMapper = objectMapper
        this.codeStorage = codeStorage
    }

    @Override
    List<SkillDefinition> loadAll() {
        return loadAllSummaries().collect { hydrateCode(it) }
    }

    @Override
    List<SkillDefinition> loadAllSummaries() {
        return jdbcTemplate.query('''
            SELECT id, name, version, language, entry_class, enabled, status, checksum, metadata::text AS metadata, created_at, updated_at
            FROM skills
            ORDER BY created_at ASC
        ''', skillSummaryMapper())
    }

    @Override
    Optional<SkillDefinition> findById(String id) {
        return findSummaryById(id).map { hydrateCode(it) }
    }

    @Override
    Optional<SkillDefinition> findSummaryById(String id) {
        try {
            SkillDefinition skill = jdbcTemplate.queryForObject('''
                SELECT id, name, version, language, entry_class, enabled, status, checksum, metadata::text AS metadata, created_at, updated_at
                FROM skills
                WHERE id = ?
            ''', skillSummaryMapper(), id)
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
        codeStorage.writeLatest(skill)
        return skill
    }

    @Override
    void delete(String id) {
        jdbcTemplate.update('DELETE FROM skills WHERE id = ?', id)
        codeStorage.delete(id)
    }

    private SkillDefinition hydrateCode(SkillDefinition skill) {
        if (!skill?.id) {
            return skill
        }
        Optional<String> localCode = codeStorage.readFreshCode(skill)
        if (localCode.present) {
            skill.code = localCode.get()
            return skill
        }
        Optional<String> dbCode = findCodeById(skill.id)
        if (dbCode.present) {
            skill.code = dbCode.get()
            codeStorage.writeLatest(skill)
        }
        return skill
    }

    private Optional<String> findCodeById(String id) {
        try {
            return Optional.ofNullable(jdbcTemplate.queryForObject('SELECT code FROM skills WHERE id = ?', String, id))
        } catch (EmptyResultDataAccessException ignored) {
            return Optional.empty()
        }
    }

    private RowMapper<SkillDefinition> skillSummaryMapper() {
        return { ResultSet rs, int rowNum -> mapSkillSummary(rs) } as RowMapper<SkillDefinition>
    }

    private SkillDefinition mapSkillSummary(ResultSet rs) {
        return new SkillDefinition(
            id: rs.getString('id'),
            name: rs.getString('name'),
            version: rs.getString('version'),
            language: rs.getString('language'),
            entryClass: rs.getString('entry_class'),
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
