package com.evoforge.llm

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
class PostgresModelProviderConfigStore implements ModelProviderConfigStore {
    private final JdbcTemplate jdbcTemplate
    private final ObjectMapper objectMapper

    PostgresModelProviderConfigStore(JdbcTemplate jdbcTemplate, ObjectMapper objectMapper) {
        this.jdbcTemplate = jdbcTemplate
        this.objectMapper = objectMapper
    }

    @Override
    List<ModelProviderConfig> loadAll() {
        return jdbcTemplate.query('''
            SELECT id, name, provider_type, base_url, model_name, api_key, enabled,
                   supports_llm, supports_code_model, default_llm, default_code_model,
                   metadata::text AS metadata, created_at, updated_at
            FROM model_provider_configs
            ORDER BY created_at ASC
        ''', configMapper())
    }

    @Override
    Optional<ModelProviderConfig> findById(String id) {
        try {
            ModelProviderConfig config = jdbcTemplate.queryForObject('''
                SELECT id, name, provider_type, base_url, model_name, api_key, enabled,
                       supports_llm, supports_code_model, default_llm, default_code_model,
                       metadata::text AS metadata, created_at, updated_at
                FROM model_provider_configs
                WHERE id = ?
            ''', configMapper(), id)
            return Optional.ofNullable(config)
        } catch (EmptyResultDataAccessException ignored) {
            return Optional.empty()
        }
    }

    @Override
    ModelProviderConfig save(ModelProviderConfig config) {
        Instant createdAt = config.createdAt ?: Instant.now()
        Instant updatedAt = Instant.now()
        config.createdAt = createdAt
        config.updatedAt = updatedAt

        jdbcTemplate.update('''
            INSERT INTO model_provider_configs (
                id, name, provider_type, base_url, model_name, api_key, enabled,
                supports_llm, supports_code_model, default_llm, default_code_model,
                metadata, created_at, updated_at
            )
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?::jsonb, ?, ?)
            ON CONFLICT (id) DO UPDATE SET
                name = EXCLUDED.name,
                provider_type = EXCLUDED.provider_type,
                base_url = EXCLUDED.base_url,
                model_name = EXCLUDED.model_name,
                api_key = EXCLUDED.api_key,
                enabled = EXCLUDED.enabled,
                supports_llm = EXCLUDED.supports_llm,
                supports_code_model = EXCLUDED.supports_code_model,
                default_llm = EXCLUDED.default_llm,
                default_code_model = EXCLUDED.default_code_model,
                metadata = EXCLUDED.metadata,
                updated_at = EXCLUDED.updated_at
        ''',
            config.id,
            config.name,
            config.providerType,
            config.baseUrl ?: '',
            config.modelName,
            config.apiKey ?: '',
            config.enabled,
            config.supportsLlm,
            config.supportsCodeModel,
            config.defaultLlm,
            config.defaultCodeModel,
            JsonColumns.writeMap(objectMapper, config.metadata),
            Timestamp.from(createdAt),
            Timestamp.from(updatedAt)
        )
        return config
    }

    @Override
    void delete(String id) {
        jdbcTemplate.update('DELETE FROM model_provider_configs WHERE id = ?', id)
    }

    private RowMapper<ModelProviderConfig> configMapper() {
        return { ResultSet rs, int rowNum -> mapConfig(rs) } as RowMapper<ModelProviderConfig>
    }

    private ModelProviderConfig mapConfig(ResultSet rs) {
        return new ModelProviderConfig(
            id: rs.getString('id'),
            name: rs.getString('name'),
            providerType: rs.getString('provider_type'),
            baseUrl: rs.getString('base_url'),
            modelName: rs.getString('model_name'),
            apiKey: rs.getString('api_key'),
            enabled: rs.getBoolean('enabled'),
            supportsLlm: rs.getBoolean('supports_llm'),
            supportsCodeModel: rs.getBoolean('supports_code_model'),
            defaultLlm: rs.getBoolean('default_llm'),
            defaultCodeModel: rs.getBoolean('default_code_model'),
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
