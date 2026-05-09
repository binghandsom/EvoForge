package com.evoforge.core

import org.springframework.boot.context.properties.ConfigurationProperties

@ConfigurationProperties(prefix = 'evoforge')
class EvoForgeProperties {
    Database database = new Database()
    Skills skills = new Skills()
    DeviceAgent deviceAgent = new DeviceAgent()
    CodexTask codexTask = new CodexTask()
    Tester tester = new Tester()
    Models models = new Models()
    AgentRuntime agent = new AgentRuntime()
    SelfLearning selfLearning = new SelfLearning()

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
        String codeStoragePath = 'data/skill-code-cache'
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
        int maxThreadTurns = 20
        String knowledgeStorage = 'data/agent-knowledge.json'
        String conversationStorage = 'data/agent-conversations.json'
        boolean shellEnabled = true
        int shellTimeoutSeconds = 10
        int fileListLimit = 200
        boolean skillFallbackEnabled = true
        int skillProposalTimeoutSeconds = 1800
    }

    static class SelfLearning {
        boolean enabled = true
        boolean autoStart = true
        String resourceMode = 'adaptive'
        boolean networkLearningEnabled = true
        boolean hardwareAccelerationEnabled = true
        boolean preferLocalHardware = true
        int targetImprovementCycleMinutes = 30
        int maxParallelLearningTasks = 4
        int maxNetworkFetchesPerCycle = 16
        int maxCandidateSourcesPerTopic = 8
        int maxHardwareUtilizationPercent = 85
        int maxRecentActivities = 80
        int maxMilestones = 30
        int maxInterfaceExamples = 12
    }

    static class DeviceAgent {
        boolean enabled = false
        String userId = 'local-user'
        String deviceId = 'local-pc'
        String commandExchange = 'evoforge.commands'
        String eventExchange = 'evoforge.events'
        String commandQueue = ''
        String commandRoutingKey = ''
        String requestQueue = ''
        String requestRoutingKey = ''
        String eventQueue = ''
        String eventRoutingKey = ''
        String commandSigningSecret = ''
        String eventSigningSecret = ''
        int commandSignatureTtlSeconds = 300
        int prefetch = 1
        int concurrentConsumers = 2
        int heartbeatSeconds = 30
    }

    static class CodexTask {
        boolean enabled = false
        boolean requiresApproval = true
        String command = 'codex'
        String workingDirectory = '.'
        String defaultWorkspace = ''
        Map<String, String> workspaces = [:]
        String bridgeBaseUrl = 'http://localhost:18080'
        String promptArg = ''
        List<String> extraArgs = []
        int timeoutSeconds = 600
        int questionTimeoutSeconds = 1800
    }

    static class Tester {
        boolean enabled = true
        boolean requiresApproval = false
        boolean recordKnowledge = true
        boolean autoRepairEnabled = false
        boolean autoDiscoverEnabled = true
        boolean modelDiscoveryEnabled = false
        boolean autoOptimizeEnabled = true
        int maxRepairAttempts = 1
        int timeoutSeconds = 600
        int maxOutputChars = 20000
        String capabilityStorage = 'data/tester-capabilities.json'
        List<TesterCommand> defaultCommands = []
        Map<String, List<TesterCommand>> projectCommands = [:]
    }

    static class TesterCommand {
        String id = ''
        String name = ''
        String type = ''
        String workingDirectory = ''
        String command = ''
        boolean enabled = true
        int timeoutSeconds = 0
        String reason = ''
        List<String> covers = []
        List<String> tags = []
        String cost = ''
        String confidence = ''
        String evidenceParser = ''
        List<String> fallbackCommandIds = []
        List<String> repairScopes = []
    }
}
