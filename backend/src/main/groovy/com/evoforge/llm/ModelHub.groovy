package com.evoforge.llm

import com.evoforge.core.EvoForgeProperties
import org.springframework.stereotype.Component

@Component
class ModelHub {
    private final Map<String, LlmProvider> llmProviders
    private final Map<String, CodeModelProvider> codeProviders
    private final EvoForgeProperties properties
    private final ModelProviderConfigService configService
    private final ConfiguredModelClientFactory configuredClientFactory

    ModelHub(List<LlmProvider> llmProviders,
             List<CodeModelProvider> codeProviders,
             EvoForgeProperties properties,
             ModelProviderConfigService configService,
             ConfiguredModelClientFactory configuredClientFactory) {
        this.llmProviders = llmProviders.collectEntries { [(it.name()): it] }
        this.codeProviders = codeProviders.collectEntries { [(it.name()): it] }
        this.properties = properties
        this.configService = configService
        this.configuredClientFactory = configuredClientFactory
    }

    LlmClient getLlm(String name = null) {
        if (name) {
            Optional<ModelProviderConfig> configured = configService.findEnabledLlm(name)
            if (configured.isPresent()) {
                return configuredClientFactory.llmClient(configured.get())
            }
        } else {
            Optional<ModelProviderConfig> configuredDefault = configService.defaultLlm()
            if (configuredDefault.isPresent()) {
                return configuredClientFactory.llmClient(configuredDefault.get())
            }
        }

        String resolved = name ?: properties.models.defaultLlm
        return llmProviders.get(resolved)?.client() ?: llmProviders.values().first().client()
    }

    CodeModelClient getCodeModel(String name = null) {
        if (name) {
            Optional<ModelProviderConfig> configured = configService.findEnabledCodeModel(name)
            if (configured.isPresent()) {
                return configuredClientFactory.codeModelClient(configured.get())
            }
        } else {
            Optional<ModelProviderConfig> configuredDefault = configService.defaultCodeModel()
            if (configuredDefault.isPresent()) {
                return configuredClientFactory.codeModelClient(configuredDefault.get())
            }
        }

        String resolved = name ?: properties.models.defaultCodeModel
        return codeProviders.get(resolved)?.client() ?: codeProviders.values().first().client()
    }

    List<String> listLlmProviders() {
        return (llmProviders.keySet() + configService.list().findAll { it.enabled && it.supportsLlm }.collect { it.id }).unique().sort()
    }

    List<String> listCodeProviders() {
        return (codeProviders.keySet() + configService.list().findAll { it.enabled && it.supportsCodeModel }.collect { it.id }).unique().sort()
    }
}
