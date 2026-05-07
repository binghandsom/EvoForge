package com.evoforge.core

import org.springframework.boot.context.properties.ConfigurationProperties

@ConfigurationProperties(prefix = 'evoforge')
class EvoForgeProperties {
    Database database = new Database()
    Skills skills = new Skills()
    DeviceAgent deviceAgent = new DeviceAgent()
    CodexTask codexTask = new CodexTask()
    Models models = new Models()
    AgentRuntime agent = new AgentRuntime()

    static class Database {
        String url = 'jdbc:postgresql://localhost:5432/evoforge'
        String username = 'postgres'
        String password = ''
        int maximumPoolSize = 5
    }

    static class Skills {
        String storageBackend = 'postgres'
        String storage = 'data/skills.json'
        String auditStorage = 'data/skill-audit.json'
        String historyStorage = 'data/skill-history.json'
        boolean gitLibraryEnabled = true
        boolean gitLibraryBootstrapOnEmpty = true
        String gitLibraryPath = 'skills'
        int autoReloadSeconds = 5
        int maxCodeSize = 0
        List<String> bannedPatterns = []
        boolean sandboxEnabled = false
        List<String> allowedImports = []
        List<String> allowedStarImports = []
        List<String> disallowedImports = []
        boolean routerUseLlm = false
        double routerMinConfidence = 0.4
        int routerCandidateLimit = 6
        double routerMinCandidateScore = 1.0
    }

    static class Models {
        String defaultLlm = 'mock-llm'
        String defaultCodeModel = 'mock-code'
        String configStorage = 'data/model-providers.json'
    }

    static class AgentRuntime {
        boolean enabled = true
        int maxSteps = 8
        int maxKnowledgeResults = 8
        String knowledgeStorage = 'data/agent-knowledge.json'
        boolean shellEnabled = true
        int shellTimeoutSeconds = 10
        int fileListLimit = 200
        List<String> shellAllowedCommands = [
            'pwd',
            'whoami',
            'id',
            'uname',
            'sw_vers',
            'ls',
            'find',
            'stat',
            'mdls',
            'xdg-user-dir',
            'cmd',
            'powershell'
        ]
    }

    static class DeviceAgent {
        boolean enabled = false
        String userId = 'local-user'
        String deviceId = 'local-pc'
        String commandExchange = 'evoforge.commands'
        String eventExchange = 'evoforge.events'
        String commandQueue = ''
        String commandRoutingKey = ''
        String eventQueue = ''
        String eventRoutingKey = ''
        String commandSigningSecret = ''
        String eventSigningSecret = ''
        int commandSignatureTtlSeconds = 300
        int prefetch = 1
        int heartbeatSeconds = 30
    }

    static class CodexTask {
        boolean enabled = false
        boolean requiresApproval = true
        String command = 'codex'
        String workingDirectory = '.'
        String defaultWorkspace = ''
        Map<String, String> workspaces = [:]
        String promptArg = ''
        List<String> extraArgs = []
        int timeoutSeconds = 600
    }
}
