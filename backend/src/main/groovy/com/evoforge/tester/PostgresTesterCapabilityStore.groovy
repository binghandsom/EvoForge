package com.evoforge.tester

import com.evoforge.core.JsonColumns
import com.fasterxml.jackson.databind.ObjectMapper
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
class PostgresTesterCapabilityStore implements TesterCapabilityStore {
    private final JdbcTemplate jdbcTemplate
    private final ObjectMapper objectMapper

    PostgresTesterCapabilityStore(JdbcTemplate jdbcTemplate, ObjectMapper objectMapper) {
        this.jdbcTemplate = jdbcTemplate
        this.objectMapper = objectMapper
    }

    @Override
    List<TesterCapability> loadAll() {
        return jdbcTemplate.query(baseSelect() + ' ORDER BY project_key ASC, capability_id ASC', mapper())
    }

    @Override
    List<TesterCapability> findByProject(String projectKey) {
        return jdbcTemplate.query(baseSelect() + ' WHERE project_key = ? ORDER BY capability_id ASC', mapper(), projectKey)
    }

    @Override
    Optional<TesterCapability> findByProjectAndId(String projectKey, String id) {
        try {
            TesterCapability capability = jdbcTemplate.queryForObject(
                baseSelect() + ' WHERE project_key = ? AND capability_id = ?',
                mapper(),
                projectKey,
                id
            )
            return Optional.ofNullable(capability)
        } catch (EmptyResultDataAccessException ignored) {
            return Optional.empty()
        }
    }

    @Override
    TesterCapability save(TesterCapability capability) {
        capability.recordId = capability.recordId ?: UUID.randomUUID().toString()
        capability.createdAt = capability.createdAt ?: Instant.now()
        capability.updatedAt = Instant.now()
        jdbcTemplate.update('''
            INSERT INTO tester_capabilities (
                record_id, project_key, capability_id, name, capability_type,
                working_directory, command, enabled, timeout_seconds, reason,
                covers, tags, cost, confidence, evidence_parser,
                fallback_command_ids, repair_scopes, source, optimization_notes,
                success_count, failure_count, last_status, last_exit_code,
                last_duration_ms, last_output_excerpt, last_run_at, metadata,
                created_at, updated_at
            )
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?::text[], ?::text[], ?, ?, ?, ?::text[],
                    ?::text[], ?, ?, ?, ?, ?, ?, ?, ?, ?, ?::jsonb, ?, ?)
            ON CONFLICT (project_key, capability_id) DO UPDATE SET
                name = EXCLUDED.name,
                capability_type = EXCLUDED.capability_type,
                working_directory = EXCLUDED.working_directory,
                command = EXCLUDED.command,
                enabled = EXCLUDED.enabled,
                timeout_seconds = EXCLUDED.timeout_seconds,
                reason = EXCLUDED.reason,
                covers = EXCLUDED.covers,
                tags = EXCLUDED.tags,
                cost = EXCLUDED.cost,
                confidence = EXCLUDED.confidence,
                evidence_parser = EXCLUDED.evidence_parser,
                fallback_command_ids = EXCLUDED.fallback_command_ids,
                repair_scopes = EXCLUDED.repair_scopes,
                source = EXCLUDED.source,
                optimization_notes = EXCLUDED.optimization_notes,
                success_count = EXCLUDED.success_count,
                failure_count = EXCLUDED.failure_count,
                last_status = EXCLUDED.last_status,
                last_exit_code = EXCLUDED.last_exit_code,
                last_duration_ms = EXCLUDED.last_duration_ms,
                last_output_excerpt = EXCLUDED.last_output_excerpt,
                last_run_at = EXCLUDED.last_run_at,
                metadata = EXCLUDED.metadata,
                updated_at = EXCLUDED.updated_at
        ''',
            capability.recordId,
            capability.projectKey,
            capability.id,
            capability.name,
            capability.type ?: '',
            capability.workingDirectory ?: '',
            capability.command,
            capability.enabled,
            capability.timeoutSeconds,
            capability.reason ?: '',
            toPostgresTextArray(capability.covers ?: []),
            toPostgresTextArray(capability.tags ?: []),
            capability.cost ?: '',
            capability.confidence ?: '',
            capability.evidenceParser ?: '',
            toPostgresTextArray(capability.fallbackCommandIds ?: []),
            toPostgresTextArray(capability.repairScopes ?: []),
            capability.source ?: 'manual',
            capability.optimizationNotes ?: '',
            capability.successCount,
            capability.failureCount,
            capability.lastStatus ?: '',
            capability.lastExitCode,
            capability.lastDurationMs,
            capability.lastOutputExcerpt ?: '',
            capability.lastRunAt ? Timestamp.from(capability.lastRunAt) : null,
            JsonColumns.writeMap(objectMapper, capability.metadata ?: [:]),
            Timestamp.from(capability.createdAt),
            Timestamp.from(capability.updatedAt)
        )
        return findByProjectAndId(capability.projectKey, capability.id).orElse(capability)
    }

    @Override
    void delete(String projectKey, String id) {
        jdbcTemplate.update('DELETE FROM tester_capabilities WHERE project_key = ? AND capability_id = ?', projectKey, id)
    }

    private static String baseSelect() {
        return '''
            SELECT record_id, project_key, capability_id, name, capability_type,
                   working_directory, command, enabled, timeout_seconds, reason,
                   covers, tags, cost, confidence, evidence_parser,
                   fallback_command_ids, repair_scopes, source, optimization_notes,
                   success_count, failure_count, last_status, last_exit_code,
                   last_duration_ms, last_output_excerpt, last_run_at,
                   metadata::text AS metadata, created_at, updated_at
            FROM tester_capabilities
        '''
    }

    private RowMapper<TesterCapability> mapper() {
        return { ResultSet rs, int rowNum -> mapCapability(rs) } as RowMapper<TesterCapability>
    }

    private TesterCapability mapCapability(ResultSet rs) {
        return new TesterCapability(
            recordId: rs.getString('record_id'),
            projectKey: rs.getString('project_key'),
            id: rs.getString('capability_id'),
            name: rs.getString('name'),
            type: rs.getString('capability_type'),
            workingDirectory: rs.getString('working_directory'),
            command: rs.getString('command'),
            enabled: rs.getBoolean('enabled'),
            timeoutSeconds: rs.getInt('timeout_seconds'),
            reason: rs.getString('reason'),
            covers: textArray(rs.getArray('covers')),
            tags: textArray(rs.getArray('tags')),
            cost: rs.getString('cost'),
            confidence: rs.getString('confidence'),
            evidenceParser: rs.getString('evidence_parser'),
            fallbackCommandIds: textArray(rs.getArray('fallback_command_ids')),
            repairScopes: textArray(rs.getArray('repair_scopes')),
            source: rs.getString('source'),
            optimizationNotes: rs.getString('optimization_notes'),
            successCount: rs.getInt('success_count'),
            failureCount: rs.getInt('failure_count'),
            lastStatus: rs.getString('last_status'),
            lastExitCode: (Integer) rs.getObject('last_exit_code'),
            lastDurationMs: (Long) rs.getObject('last_duration_ms'),
            lastOutputExcerpt: rs.getString('last_output_excerpt'),
            lastRunAt: readInstant(rs, 'last_run_at'),
            metadata: JsonColumns.readMap(objectMapper, rs.getString('metadata')),
            createdAt: readInstant(rs, 'created_at'),
            updatedAt: readInstant(rs, 'updated_at')
        )
    }

    private static List<String> textArray(Array array) {
        return array ? ((Object[]) array.array).collect { it.toString() } : []
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

