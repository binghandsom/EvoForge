package com.evoforge.llm

import org.springframework.stereotype.Service

import java.text.Normalizer
import java.time.Instant

@Service
class ModelProviderConfigService {
    static final Set<String> SUPPORTED_PROVIDER_TYPES = ['openai', 'openai-compatible', 'anthropic'] as Set

    private final ModelProviderConfigStore store

    ModelProviderConfigService(ModelProviderConfigStore store) {
        this.store = store
    }

    List<ModelProviderConfig> list() {
        return store.loadAll()
    }

    Optional<ModelProviderConfig> findEnabledLlm(String id) {
        return findEnabled(id, true, false)
    }

    Optional<ModelProviderConfig> findEnabledCodeModel(String id) {
        return findEnabled(id, false, true)
    }

    Optional<ModelProviderConfig> defaultLlm() {
        return Optional.ofNullable(store.loadAll().find { it.enabled && it.supportsLlm && it.defaultLlm })
    }

    Optional<ModelProviderConfig> defaultCodeModel() {
        return Optional.ofNullable(store.loadAll().find { it.enabled && it.supportsCodeModel && it.defaultCodeModel })
    }

    ModelProviderConfig upsert(Map<String, Object> request) {
        String requestedId = text(request.id)
        Optional<ModelProviderConfig> existing = requestedId ? store.findById(requestedId) : Optional.empty()
        ModelProviderConfig config = existing.orElseGet { new ModelProviderConfig(id: requestedId ?: newId(request)) }

        if (request.containsKey('name')) {
            config.name = requiredText(request.name, 'name')
        } else if (!config.name) {
            config.name = requiredText(request.modelName ?: request.id, 'name')
        }
        if (request.containsKey('providerType')) {
            config.providerType = normalizeProviderType(request.providerType)
        } else {
            config.providerType = normalizeProviderType(config.providerType)
        }
        if (request.containsKey('baseUrl')) {
            config.baseUrl = text(request.baseUrl)
        }
        if (request.containsKey('modelName')) {
            config.modelName = requiredText(request.modelName, 'modelName')
        } else if (!config.modelName) {
            throw new IllegalArgumentException('modelName is required')
        }
        if (request.containsKey('apiKey')) {
            String apiKey = text(request.apiKey)
            if (apiKey) {
                config.apiKey = apiKey
            }
        }
        if (request.containsKey('enabled')) {
            config.enabled = asBoolean(request.enabled, true)
        }
        if (request.containsKey('supportsLlm')) {
            config.supportsLlm = asBoolean(request.supportsLlm, true)
        }
        if (request.containsKey('supportsCodeModel')) {
            config.supportsCodeModel = asBoolean(request.supportsCodeModel, false)
        }
        if (request.containsKey('defaultLlm')) {
            config.defaultLlm = asBoolean(request.defaultLlm, false)
        }
        if (request.containsKey('defaultCodeModel')) {
            config.defaultCodeModel = asBoolean(request.defaultCodeModel, false)
        }
        if (request.metadata instanceof Map) {
            config.metadata = request.metadata as Map<String, Object>
        }
        if (!config.baseUrl) {
            config.baseUrl = defaultBaseUrl(config.providerType)
        }
        if (!config.enabled) {
            config.defaultLlm = false
            config.defaultCodeModel = false
        }
        if (!config.supportsLlm) {
            config.defaultLlm = false
        }
        if (!config.supportsCodeModel) {
            config.defaultCodeModel = false
        }

        enforceSingleDefaults(config)
        return store.save(config)
    }

    void delete(String id) {
        store.delete(id)
    }

    static Map<String, Object> toView(ModelProviderConfig config) {
        return [
            id               : config.id,
            name             : config.name,
            providerType     : config.providerType,
            baseUrl          : config.baseUrl,
            modelName        : config.modelName,
            enabled          : config.enabled,
            supportsLlm      : config.supportsLlm,
            supportsCodeModel: config.supportsCodeModel,
            defaultLlm       : config.defaultLlm,
            defaultCodeModel : config.defaultCodeModel,
            apiKeyConfigured : !!config.apiKey,
            maskedApiKey     : maskSecret(config.apiKey),
            metadata         : config.metadata ?: [:],
            createdAt        : config.createdAt,
            updatedAt        : config.updatedAt
        ]
    }

    static String defaultBaseUrl(String providerType) {
        switch (providerType) {
            case 'anthropic':
                return 'https://api.anthropic.com/v1'
            case 'openai':
            case 'openai-compatible':
            default:
                return 'https://api.openai.com/v1'
        }
    }

    private Optional<ModelProviderConfig> findEnabled(String id, boolean llm, boolean codeModel) {
        String resolved = text(id)
        if (!resolved) {
            return Optional.empty()
        }
        return store.findById(resolved).filter {
            it.enabled && (!llm || it.supportsLlm) && (!codeModel || it.supportsCodeModel)
        }
    }

    private void enforceSingleDefaults(ModelProviderConfig selected) {
        if (!selected.defaultLlm && !selected.defaultCodeModel) {
            return
        }
        store.loadAll().findAll { it.id != selected.id }.each { other ->
            boolean changed = false
            if (selected.defaultLlm && other.defaultLlm) {
                other.defaultLlm = false
                changed = true
            }
            if (selected.defaultCodeModel && other.defaultCodeModel) {
                other.defaultCodeModel = false
                changed = true
            }
            if (changed) {
                store.save(other)
            }
        }
    }

    private static String newId(Map<String, Object> request) {
        String source = text(request.name) ?: text(request.modelName) ?: UUID.randomUUID().toString()
        String normalized = Normalizer.normalize(source, Normalizer.Form.NFD)
            .replaceAll('\\p{M}', '')
            .toLowerCase(Locale.ROOT)
            .replaceAll('[^a-z0-9]+', '-')
            .replaceAll('(^-|-$)', '')
        String suffix = UUID.randomUUID().toString().substring(0, 8)
        return "${normalized ?: 'model'}-${suffix}"
    }

    private static String normalizeProviderType(Object value) {
        String providerType = requiredText(value, 'providerType').toLowerCase(Locale.ROOT).replace('_', '-')
        if (!SUPPORTED_PROVIDER_TYPES.contains(providerType)) {
            throw new IllegalArgumentException("Unsupported providerType: ${providerType}")
        }
        return providerType
    }

    private static boolean asBoolean(Object value, boolean fallback) {
        if (value == null) {
            return fallback
        }
        if (value instanceof Boolean) {
            return value
        }
        return value.toString().toBoolean()
    }

    private static String requiredText(Object value, String field) {
        String result = text(value)
        if (!result) {
            throw new IllegalArgumentException("${field} is required")
        }
        return result
    }

    private static String text(Object value) {
        return value == null ? '' : value.toString().trim()
    }

    private static String maskSecret(String value) {
        if (!value) {
            return ''
        }
        if (value.length() <= 8) {
            return 'configured'
        }
        return "${value.substring(0, 4)}...${value.substring(value.length() - 4)}"
    }
}
