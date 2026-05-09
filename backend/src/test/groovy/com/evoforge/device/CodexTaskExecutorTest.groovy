package com.evoforge.device

import com.evoforge.agent.AgentKnowledgeService
import com.evoforge.agent.AgentKnowledgeStore
import com.evoforge.agent.AgentKnowledgeFact
import com.evoforge.core.EvoForgeProperties
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

import java.time.Instant
import java.nio.file.Files
import java.nio.file.Path

import static org.junit.jupiter.api.Assertions.*

class CodexTaskExecutorTest {

    @TempDir
    Path tempDir

    @Test
    void rejectsUnknownProjectKeyInsteadOfUsingRemotePath() {
        EvoForgeProperties properties = enabledProperties()
        properties.codexTask.command = executableScript('codex-ok', '#!/bin/sh\npwd\n').toString()
        properties.codexTask.workspaces = [
            evoforge: tempDir.toString()
        ]

        CodexTaskResult result = new CodexTaskExecutor(properties).execute(new DeviceCommandMessage(
            taskId: 'task-1',
            type: DeviceProtocol.TYPE_CODEX_TASK,
            text: 'status',
            attributes: [
                projectKey: 'missing',
                path      : '/tmp/should-not-be-used'
            ]
        ))

        assertEquals(DeviceProtocol.STATUS_FAILED, result.status)
        assertTrue(result.message.contains("Codex workspace 'missing' is not configured"))
    }

    @Test
    void rejectsMissingWorkspaceDirectory() {
        EvoForgeProperties properties = enabledProperties()
        properties.codexTask.command = executableScript('codex-ok', '#!/bin/sh\npwd\n').toString()
        properties.codexTask.workspaces = [
            deleted: tempDir.resolve('deleted').toString()
        ]

        CodexTaskResult result = new CodexTaskExecutor(properties).execute(new DeviceCommandMessage(
            taskId: 'task-1',
            type: DeviceProtocol.TYPE_CODEX_TASK,
            text: 'status',
            attributes: [projectKey: 'deleted']
        ))

        assertEquals(DeviceProtocol.STATUS_FAILED, result.status)
        assertTrue(result.message.contains('Codex workspace directory does not exist'))
    }

    @Test
    void usesDefaultWorkspaceKeyWhenProjectKeyIsMissing() {
        Path workspace = Files.createDirectory(tempDir.resolve('default-workspace'))
        Path fakeCodex = executableScript('codex-ok', '#!/bin/sh\npwd\n')
        EvoForgeProperties properties = enabledProperties()
        properties.codexTask.command = fakeCodex.toString()
        properties.codexTask.defaultWorkspace = 'alpha'
        properties.codexTask.workspaces = [
            alpha: workspace.toString()
        ]

        CodexTaskResult result = new CodexTaskExecutor(properties).execute(new DeviceCommandMessage(
            taskId: 'task-1',
            type: DeviceProtocol.TYPE_CODEX_TASK,
            text: 'status'
        ))

        assertEquals(DeviceProtocol.STATUS_COMPLETED, result.status)
        assertTrue(result.output.contains(workspace.toRealPath().toString()))
    }

    @Test
    void fallsBackToLegacyWorkingDirectoryWhenDefaultWorkspaceIsMissing() {
        Path fakeCodex = executableScript('codex-ok', '#!/bin/sh\npwd\n')
        EvoForgeProperties properties = enabledProperties()
        properties.codexTask.command = fakeCodex.toString()
        properties.codexTask.workingDirectory = tempDir.toString()

        CodexTaskResult result = new CodexTaskExecutor(properties).execute(new DeviceCommandMessage(
            taskId: 'task-1',
            type: DeviceProtocol.TYPE_CODEX_TASK,
            text: 'status'
        ))

        assertEquals(DeviceProtocol.STATUS_COMPLETED, result.status)
        assertTrue(result.output.contains(tempDir.toRealPath().toString()))
    }

    @Test
    void rejectsUnconfiguredDefaultWorkspace() {
        Path fakeCodex = executableScript('codex-ok', '#!/bin/sh\npwd\n')
        EvoForgeProperties properties = enabledProperties()
        properties.codexTask.command = fakeCodex.toString()
        properties.codexTask.defaultWorkspace = 'missing'
        properties.codexTask.workspaces = [
            alpha: tempDir.toString()
        ]

        CodexTaskResult result = new CodexTaskExecutor(properties).execute(new DeviceCommandMessage(
            taskId: 'task-1',
            type: DeviceProtocol.TYPE_CODEX_TASK,
            text: 'status'
        ))

        assertEquals(DeviceProtocol.STATUS_FAILED, result.status)
        assertTrue(result.message.contains("Default Codex workspace 'missing' is not configured"))
    }

    @Test
    void usesConfiguredWorkspaceForProjectKey() {
        Path workspace = Files.createDirectory(tempDir.resolve('workspace-a'))
        Path fakeCodex = executableScript('codex-ok', '#!/bin/sh\npwd\n')
        EvoForgeProperties properties = enabledProperties()
        properties.codexTask.command = fakeCodex.toString()
        properties.codexTask.workingDirectory = tempDir.toString()
        properties.codexTask.workspaces = [
            alpha: workspace.toString()
        ]

        CodexTaskResult result = new CodexTaskExecutor(properties).execute(new DeviceCommandMessage(
            taskId: 'task-1',
            type: DeviceProtocol.TYPE_CODEX_TASK,
            text: 'status',
            attributes: [projectKey: 'alpha']
        ))

        assertEquals(DeviceProtocol.STATUS_COMPLETED, result.status)
        assertTrue(result.output.contains(workspace.toRealPath().toString()))
    }

    @Test
    void includesEvoForgeBridgeInstructionsWhenLearningIsEnabled() {
        Path workspace = Files.createDirectory(tempDir.resolve('workspace-a'))
        Path fakeCodex = executableScript('codex-echo', '#!/bin/sh\nprintf "%s\\n" "$@"\n')
        EvoForgeProperties properties = enabledProperties()
        properties.codexTask.command = fakeCodex.toString()
        properties.codexTask.bridgeBaseUrl = 'http://localhost:18080'
        properties.codexTask.workspaces = [
            alpha: workspace.toString()
        ]

        CodexTaskResult result = new CodexTaskExecutor(
            properties,
            new AgentKnowledgeService(new InMemoryKnowledgeStore())
        ).execute(new DeviceCommandMessage(
            taskId: 'task-bridge',
            type: DeviceProtocol.TYPE_CODEX_TASK,
            text: '修复指挥台项目知识检索',
            attributes: [
                projectKey      : 'alpha',
                evoforgeLearning: [enabled: true, sourceProjectKey: 'alpha']
            ]
        ))

        assertEquals(DeviceProtocol.STATUS_COMPLETED, result.status)
        assertTrue(result.output.contains('EvoForge bridge available'))
        assertTrue(result.output.contains('/api/codex/bridge/query'))
        assertTrue(result.output.contains('"projectKey":"alpha"'))
    }

    @Test
    void includesTesterBridgeInstructionsWhenTesterLaneIsEnabled() {
        Path workspace = Files.createDirectory(tempDir.resolve('workspace-tester'))
        Path fakeCodex = executableScript('codex-echo', '#!/bin/sh\nprintf "%s\\n" "$@"\n')
        EvoForgeProperties properties = enabledProperties()
        properties.codexTask.command = fakeCodex.toString()
        properties.codexTask.bridgeBaseUrl = 'http://localhost:18080'
        properties.codexTask.workspaces = [
            alpha: workspace.toString()
        ]

        CodexTaskResult result = new CodexTaskExecutor(properties).execute(new DeviceCommandMessage(
            taskId: 'task-tester',
            type: DeviceProtocol.TYPE_CODEX_TASK,
            text: '实现后请让 EvoForge 测试员验证',
            attributes: [
                projectKey     : 'alpha',
                evoforgeTester : [enabled: true]
            ]
        ))

        assertEquals(DeviceProtocol.STATUS_COMPLETED, result.status)
        assertTrue(result.output.contains('/api/codex/bridge/test-plan'))
        assertTrue(result.output.contains('/api/codex/bridge/test-run'))
    }

    private EvoForgeProperties enabledProperties() {
        EvoForgeProperties properties = new EvoForgeProperties()
        properties.codexTask.enabled = true
        properties.codexTask.command = '/bin/echo'
        properties.codexTask.workingDirectory = tempDir.toString()
        properties.codexTask.timeoutSeconds = 5
        return properties
    }

    private Path executableScript(String name, String content) {
        Path script = tempDir.resolve(name)
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
}
