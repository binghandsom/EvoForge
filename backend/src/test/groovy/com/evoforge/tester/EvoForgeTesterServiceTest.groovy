package com.evoforge.tester

import com.evoforge.agent.AgentKnowledgeFact
import com.evoforge.agent.AgentKnowledgeService
import com.evoforge.agent.AgentKnowledgeStore
import com.evoforge.agent.ProjectKnowledgeContextService
import com.evoforge.core.EvoForgeProperties
import com.evoforge.device.DeviceProtocol
import com.fasterxml.jackson.databind.ObjectMapper
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant

import static org.junit.jupiter.api.Assertions.*

class EvoForgeTesterServiceTest {

    @TempDir
    Path tempDir

    private final ObjectMapper objectMapper = new ObjectMapper()

    @Test
    void runsConfiguredProjectTesterCommandsAndRecordsKnowledge() {
        Path workspace = Files.createDirectory(tempDir.resolve('workspace'))
        Path script = executableScript(workspace, 'test-ok', '#!/bin/sh\necho tester-ok\n')
        EvoForgeProperties properties = propertiesFor(workspace)
        properties.tester.projectCommands = [
            alpha: [
                new EvoForgeProperties.TesterCommand(
                    id: 'unit',
                    name: 'Unit Tests',
                    workingDirectory: '.',
                    command: './test-ok',
                    reason: 'fast regression check'
                )
            ]
        ]
        InMemoryKnowledgeStore store = new InMemoryKnowledgeStore()
        EvoForgeTesterService service = service(properties, store)

        TesterRunResult result = service.run([
            taskId    : 'task-12345678',
            projectKey: 'alpha',
            task      : '验证指挥台测试员能力'
        ])

        assertEquals(DeviceProtocol.STATUS_COMPLETED, result.status)
        assertTrue(result.output.contains('tester-ok'))
        assertEquals('unit', result.commands.first().id)
        assertEquals('.', result.commands.first().workingDirectory)
        assertTrue(Files.isExecutable(script))

        AgentKnowledgeFact latest = store.findByKey('project.alpha.tester.latest', 'project:alpha').orElse(null)
        assertNotNull(latest)
        Map value = objectMapper.readValue(latest.value, Map)
        assertEquals('tester-run', value.kind)
        assertEquals(true, value.passed)
        assertEquals('task-12345678', value.taskId)
    }

    @Test
    void refusesUnknownWorkspaceInsteadOfUsingRequestPath() {
        EvoForgeProperties properties = propertiesFor(tempDir)
        EvoForgeTesterService service = service(properties, new InMemoryKnowledgeStore())

        TesterRunResult result = service.run([
            projectKey: 'missing',
            path      : '/tmp/should-not-be-used',
            task      : 'run tests'
        ])

        assertEquals(DeviceProtocol.STATUS_FAILED, result.status)
        assertTrue(result.message.contains("Tester workspace 'missing' is not configured"))
    }

    @Test
    void filtersToConfiguredCommandIdsOnly() {
        Path workspace = Files.createDirectory(tempDir.resolve('workspace'))
        executableScript(workspace, 'test-ok', '#!/bin/sh\necho tester-ok\n')
        EvoForgeProperties properties = propertiesFor(workspace)
        properties.tester.projectCommands = [
            alpha: [
                new EvoForgeProperties.TesterCommand(id: 'unit', name: 'Unit Tests', command: './test-ok')
            ]
        ]
        EvoForgeTesterService service = service(properties, new InMemoryKnowledgeStore())

        TesterRunResult result = service.run([
            projectKey : 'alpha',
            task       : 'run tests',
            commandIds : ['not-configured'],
            command    : 'rm -rf /'
        ])

        assertEquals(DeviceProtocol.STATUS_FAILED, result.status)
        assertTrue(result.message.contains('No tester capabilities are configured'))
    }

    @Test
    void planExposesOnlyConfiguredTesterCommands() {
        Path workspace = Files.createDirectory(tempDir.resolve('workspace'))
        executableScript(workspace, 'test-ok', '#!/bin/sh\necho tester-ok\n')
        EvoForgeProperties properties = propertiesFor(workspace)
        properties.tester.projectCommands = [
            alpha: [
                new EvoForgeProperties.TesterCommand(id: 'unit', name: 'Unit Tests', command: './test-ok')
            ]
        ]
        EvoForgeTesterService service = service(properties, new InMemoryKnowledgeStore())

        Map plan = service.plan([projectKey: 'alpha', task: '验证测试员计划'], 'http://localhost:18080/')

        assertEquals('evoforge-tester', plan.tester)
        assertEquals(true, plan.canRun)
        assertEquals('unit', (plan.commands as List).first().id)
        assertFalse((plan.routes as List).isEmpty())
        assertEquals('fast-feedback', plan.recommendedRoute.id)
        assertTrue((plan.riskProfile.impactedCovers as List).contains('testing'))
        assertTrue(plan.runEndpoint.toString().endsWith('/api/codex/bridge/test-run'))
    }

    @Test
    void planBuildsRiskRoutesFromCapabilityMetadata() {
        Path workspace = Files.createDirectory(tempDir.resolve('workspace'))
        executableScript(workspace, 'frontend-ok', '#!/bin/sh\necho frontend-ok\n')
        executableScript(workspace, 'backend-ok', '#!/bin/sh\necho backend-ok\n')
        EvoForgeProperties properties = propertiesFor(workspace)
        properties.tester.projectCommands = [
            alpha: [
                new EvoForgeProperties.TesterCommand(
                    id: 'frontend-command',
                    name: 'Frontend Command Center',
                    type: 'ui-regression',
                    covers: ['frontend', 'message-bus'],
                    tags: ['flutter'],
                    cost: 'low',
                    confidence: 'high',
                    command: './frontend-ok'
                ),
                new EvoForgeProperties.TesterCommand(
                    id: 'backend-device',
                    name: 'Backend Device Protocol',
                    type: 'backend-regression',
                    covers: ['backend', 'message-bus'],
                    tags: ['groovy'],
                    cost: 'high',
                    confidence: 'high',
                    command: './backend-ok'
                )
            ]
        ]
        EvoForgeTesterService service = service(properties, new InMemoryKnowledgeStore())

        Map plan = service.plan([
            projectKey: 'alpha',
            task: '修改 Flutter 指挥台和 RabbitMQ device protocol'
        ])

        assertEquals('medium', plan.riskProfile.level)
        assertTrue((plan.riskProfile.impactedCovers as List).contains('frontend'))
        assertTrue((plan.riskProfile.impactedCovers as List).contains('message-bus'))
        assertTrue((plan.routes as List).any { it.id == 'targeted-risk' || it.id == 'release-confidence' })
        assertEquals('frontend-command', ((plan.capabilities as List).find { it.id == 'frontend-command' }).id)
    }

    @Test
    void failedRunProducesEvidencePacketAndRepairPrompt() {
        Path workspace = Files.createDirectory(tempDir.resolve('workspace'))
        executableScript(workspace, 'test-fail', '#!/bin/sh\necho "No signature of method: java.util.ArrayList.collectWithIndex()"\necho "src/main/groovy/com/evoforge/tester/EvoForgeTesterService.groovy:237"\nexit 1\n')
        EvoForgeProperties properties = propertiesFor(workspace)
        properties.tester.projectCommands = [
            alpha: [
                new EvoForgeProperties.TesterCommand(
                    id: 'backend-tests',
                    name: 'Backend Tests',
                    covers: ['backend'],
                    evidenceParser: 'jvm-test',
                    command: './test-fail'
                )
            ]
        ]
        EvoForgeTesterService service = service(properties, new InMemoryKnowledgeStore())

        TesterRunResult result = service.run([
            projectKey: 'alpha',
            task: '修改测试员 Groovy 服务',
            routeId: 'recommended'
        ])

        assertEquals(DeviceProtocol.STATUS_FAILED, result.status)
        assertTrue(result.repairPrompt.contains('Codex repair request'))
        assertEquals('backend-tests', result.evidenceSummary.failedCapabilityIds.first())
        Map packet = result.commands.first().evidencePacket
        assertTrue(packet.likelyCause.toString().contains('Groovy API'))
        assertTrue((packet.failedFiles as List).any { it.toString().contains('EvoForgeTesterService.groovy') })
    }

    @Test
    void discoversConventionalTesterCapabilitiesAndPersistsThem() {
        Path workspace = Files.createDirectory(tempDir.resolve('workspace-discovery'))
        Files.createDirectories(workspace.resolve('backend'))
        Files.writeString(workspace.resolve('backend/pom.xml'), '<project></project>')
        Files.createDirectories(workspace.resolve('frontend'))
        Files.writeString(workspace.resolve('frontend/pubspec.yaml'), 'name: demo\n')
        EvoForgeProperties properties = propertiesFor(workspace)
        InMemoryTesterCapabilityStore capabilityStore = new InMemoryTesterCapabilityStore()
        TesterCapabilityService capabilityService = new TesterCapabilityService(
            capabilityStore,
            properties,
            objectMapper,
            null
        )

        Map result = capabilityService.discover([projectKey: 'alpha'])

        assertEquals(4, result.discovered)
        List<String> ids = capabilityStore.findByProject('alpha').collect { it.id }
        assertTrue(ids.contains('backend-tests'))
        assertTrue(ids.contains('frontend-analyze'))
        assertTrue(ids.contains('frontend-tests'))
        assertTrue(ids.contains('frontend-web-build'))
    }

    @Test
    void runResultsOptimizePersistedTesterCapabilityRecords() {
        EvoForgeProperties properties = propertiesFor(tempDir)
        InMemoryTesterCapabilityStore capabilityStore = new InMemoryTesterCapabilityStore()
        TesterCapabilityService capabilityService = new TesterCapabilityService(
            capabilityStore,
            properties,
            objectMapper,
            null
        )
        capabilityService.save([
            projectKey: 'alpha',
            id: 'unit',
            name: 'Unit Tests',
            command: 'mvn test',
            cost: 'medium',
            confidence: 'medium'
        ])

        capabilityService.recordRunResults('alpha', [
            new TesterCommandResult(
                id: 'unit',
                name: 'Unit Tests',
                status: DeviceProtocol.STATUS_COMPLETED,
                durationMs: 1200,
                output: 'ok'
            ),
            new TesterCommandResult(
                id: 'unit',
                name: 'Unit Tests',
                status: DeviceProtocol.STATUS_COMPLETED,
                durationMs: 1100,
                output: 'ok'
            )
        ])

        TesterCapability capability = capabilityStore.findByProjectAndId('alpha', 'unit').orElse(null)
        assertNotNull(capability)
        assertEquals(2, capability.successCount)
        assertEquals('high', capability.confidence)
        assertEquals(DeviceProtocol.STATUS_COMPLETED, capability.lastStatus)
        assertTrue(capability.optimizationNotes.contains('Last run passed'))
    }

    @Test
    void saveAllowsUnlistedTesterCapabilityCommands() {
        EvoForgeProperties properties = propertiesFor(tempDir)
        InMemoryTesterCapabilityStore capabilityStore = new InMemoryTesterCapabilityStore()
        TesterCapabilityService capabilityService = new TesterCapabilityService(
            capabilityStore,
            properties,
            objectMapper,
            null
        )

        TesterCapability saved = capabilityService.save([
            projectKey: 'alpha',
            id: 'custom-check',
            name: 'Custom Check',
            command: 'custom-test-runner --ci'
        ])

        assertEquals('custom-test-runner --ci', saved.command)
        assertEquals('custom-check', capabilityStore.findByProject('alpha').first().id)
    }

    @Test
    void discoveryDoesNotOverwriteManualTesterCapabilityEdits() {
        Path workspace = Files.createDirectory(tempDir.resolve('workspace-manual'))
        Files.createDirectories(workspace.resolve('backend'))
        Files.writeString(workspace.resolve('backend/pom.xml'), '<project></project>')
        EvoForgeProperties properties = propertiesFor(workspace)
        InMemoryTesterCapabilityStore capabilityStore = new InMemoryTesterCapabilityStore()
        TesterCapabilityService capabilityService = new TesterCapabilityService(
            capabilityStore,
            properties,
            objectMapper,
            null
        )
        capabilityService.save([
            projectKey: 'alpha',
            id: 'backend-tests',
            name: 'Custom Backend Check',
            workingDirectory: 'backend',
            command: 'mvn -q test',
            confidence: 'medium'
        ])

        capabilityService.discover([projectKey: 'alpha'])

        TesterCapability capability = capabilityStore.findByProjectAndId('alpha', 'backend-tests').orElse(null)
        assertNotNull(capability)
        assertEquals('manual', capability.source)
        assertEquals('Custom Backend Check', capability.name)
        assertEquals('mvn -q test', capability.command)
        assertEquals('medium', capability.confidence)
    }

    private EvoForgeTesterService service(EvoForgeProperties properties, InMemoryKnowledgeStore store) {
        AgentKnowledgeService knowledge = new AgentKnowledgeService(store)
        return new EvoForgeTesterService(
            properties,
            knowledge,
            new ProjectKnowledgeContextService(knowledge, objectMapper),
            objectMapper,
            new TesterCapabilityService(
                new InMemoryTesterCapabilityStore(),
                properties,
                objectMapper,
                null
            )
        )
    }

    private EvoForgeProperties propertiesFor(Path workspace) {
        EvoForgeProperties properties = new EvoForgeProperties()
        properties.codexTask.defaultWorkspace = 'alpha'
        properties.codexTask.workspaces = [alpha: workspace.toString()]
        properties.tester.enabled = true
        properties.tester.timeoutSeconds = 5
        properties.tester.maxOutputChars = 4000
        return properties
    }

    private static Path executableScript(Path dir, String name, String content) {
        Path script = dir.resolve(name)
        Files.writeString(script, content)
        script.toFile().setExecutable(true)
        return script
    }

    private static class InMemoryKnowledgeStore implements AgentKnowledgeStore {
        private final Map<String, AgentKnowledgeFact> facts = new LinkedHashMap<>()

        @Override
        List<AgentKnowledgeFact> loadAll() {
            return facts.values().toList()
        }

        @Override
        Optional<AgentKnowledgeFact> findByKey(String key, String scope) {
            return Optional.ofNullable(facts["${scope ?: 'global'}:${key}".toString()])
        }

        @Override
        AgentKnowledgeFact save(AgentKnowledgeFact fact) {
            fact.updatedAt = fact.updatedAt ?: Instant.now()
            facts["${fact.scope ?: 'global'}:${fact.key}".toString()] = fact
            return fact
        }

        @Override
        void delete(String id) {
            facts.values().removeIf { it.id == id }
        }
    }

    private static class InMemoryTesterCapabilityStore implements TesterCapabilityStore {
        private final Map<String, TesterCapability> capabilities = new LinkedHashMap<>()

        @Override
        List<TesterCapability> loadAll() {
            return capabilities.values().toList()
        }

        @Override
        List<TesterCapability> findByProject(String projectKey) {
            return capabilities.values().findAll { it.projectKey == projectKey }.sort { a, b -> a.id <=> b.id }
        }

        @Override
        Optional<TesterCapability> findByProjectAndId(String projectKey, String id) {
            return Optional.ofNullable(capabilities["${projectKey}:${id}".toString()])
        }

        @Override
        TesterCapability save(TesterCapability capability) {
            capability.recordId = capability.recordId ?: UUID.randomUUID().toString()
            capability.createdAt = capability.createdAt ?: Instant.now()
            capability.updatedAt = Instant.now()
            capabilities["${capability.projectKey}:${capability.id}".toString()] = capability
            return capability
        }

        @Override
        void delete(String projectKey, String id) {
            capabilities.remove("${projectKey}:${id}".toString())
        }
    }
}
