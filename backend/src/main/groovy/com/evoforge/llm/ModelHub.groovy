package com.evoforge.llm

import com.evoforge.core.EvoForgeProperties
import org.springframework.stereotype.Component

@Component
class ModelHub {
    private final Map<String, LlmProvider> llmProviders
    private final Map<String, CodeModelProvider> codeProviders
    private final EvoForgeProperties properties

    ModelHub(List<LlmProvider> llmProviders, List<CodeModelProvider> codeProviders, EvoForgeProperties properties) {
        this.llmProviders = llmProviders.collectEntries { [(it.name()): it] }
        this.codeProviders = codeProviders.collectEntries { [(it.name()): it] }
        this.properties = properties
    }

    LlmClient getLlm(String name = null) {
        String resolved = name ?: properties.models.defaultLlm
        return llmProviders.get(resolved)?.client() ?: llmProviders.values().first().client()
    }

    CodeModelClient getCodeModel(String name = null) {
        String resolved = name ?: properties.models.defaultCodeModel
        return codeProviders.get(resolved)?.client() ?: codeProviders.values().first().client()
    }

    List<String> listLlmProviders() {
        return llmProviders.keySet().sort()
    }

    List<String> listCodeProviders() {
        return codeProviders.keySet().sort()
    }
}
