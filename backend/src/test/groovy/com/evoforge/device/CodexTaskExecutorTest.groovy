package com.evoforge.device

import com.evoforge.core.EvoForgeProperties
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

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
}
