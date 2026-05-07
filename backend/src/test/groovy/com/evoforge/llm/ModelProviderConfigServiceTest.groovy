package com.evoforge.llm

import org.junit.jupiter.api.Test

import static org.junit.jupiter.api.Assertions.*

class ModelProviderConfigServiceTest {

    @Test
    void upsertsConfigsWithoutExposingOrClearingExistingKeys() {
        InMemoryModelProviderConfigStore store = new InMemoryModelProviderConfigStore()
        ModelProviderConfigService service = new ModelProviderConfigService(store)

        ModelProviderConfig gpt = service.upsert([
            name             : 'GPT 4.1',
            providerType     : 'openai',
            modelName        : 'gpt-4.1',
            apiKey           : 'sk-test-1234567890',
            supportsCodeModel: true,
            defaultLlm       : true,
            defaultCodeModel : true
        ])

        assertEquals('https://api.openai.com/v1', gpt.baseUrl)
        assertEquals('sk-t...7890', ModelProviderConfigService.toView(gpt).maskedApiKey)
        assertEquals(gpt.id, service.defaultLlm().get().id)
        assertEquals(gpt.id, service.defaultCodeModel().get().id)

        ModelProviderConfig updated = service.upsert([
            id          : gpt.id,
            name        : 'GPT 4.1 mini',
            providerType: 'openai',
            modelName   : 'gpt-4.1-mini'
        ])

        assertEquals('sk-test-1234567890', updated.apiKey)
        assertEquals('GPT 4.1 mini', updated.name)

        ModelProviderConfig claude = service.upsert([
            name        : 'Claude Sonnet',
            providerType: 'anthropic',
            modelName   : 'claude-3-7-sonnet-latest',
            apiKey      : 'sk-ant-test-1234',
            defaultLlm  : true
        ])

        assertEquals('https://api.anthropic.com/v1', claude.baseUrl)
        assertEquals(claude.id, service.defaultLlm().get().id)
        assertEquals(gpt.id, service.defaultCodeModel().get().id)
        assertFalse(store.findById(gpt.id).get().defaultLlm)
        assertTrue(store.findById(gpt.id).get().defaultCodeModel)
    }

    private static class InMemoryModelProviderConfigStore implements ModelProviderConfigStore {
        private final Map<String, ModelProviderConfig> configs = new LinkedHashMap<>()

        @Override
        List<ModelProviderConfig> loadAll() {
            return new ArrayList<>(configs.values())
        }

        @Override
        Optional<ModelProviderConfig> findById(String id) {
            return Optional.ofNullable(configs[id])
        }

        @Override
        ModelProviderConfig save(ModelProviderConfig config) {
            configs[config.id] = config
            return config
        }

        @Override
        void delete(String id) {
            configs.remove(id)
        }
    }
}
