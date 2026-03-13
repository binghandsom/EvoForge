package com.evoforge.core

import org.springframework.boot.context.properties.ConfigurationProperties

@ConfigurationProperties(prefix = 'evoforge')
class EvoForgeProperties {
    Skills skills = new Skills()
    Models models = new Models()

    static class Skills {
        String storage = 'data/skills.json'
        String auditStorage = 'data/skill-audit.json'
        String historyStorage = 'data/skill-history.json'
        int autoReloadSeconds = 5
        int maxCodeSize = 0
        List<String> bannedPatterns = []
        boolean sandboxEnabled = false
        List<String> allowedImports = []
        List<String> allowedStarImports = []
        List<String> disallowedImports = []
        boolean routerUseLlm = false
        double routerMinConfidence = 0.4
    }

    static class Models {
        String defaultLlm = 'mock-llm'
        String defaultCodeModel = 'mock-code'
    }
}
