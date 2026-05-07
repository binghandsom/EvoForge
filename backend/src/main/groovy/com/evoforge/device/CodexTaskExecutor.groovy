package com.evoforge.device

import com.evoforge.core.EvoForgeProperties
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service

import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration
import java.util.concurrent.TimeUnit

@Service
class CodexTaskExecutor {
    private static final Logger log = LoggerFactory.getLogger(CodexTaskExecutor)

    private final EvoForgeProperties properties

    CodexTaskExecutor(EvoForgeProperties properties) {
        this.properties = properties
    }

    CodexTaskResult execute(DeviceCommandMessage command) {
        EvoForgeProperties.CodexTask config = properties.codexTask
        if (!config.enabled) {
            return CodexTaskResult.disabled('Codex task execution is disabled')
        }
        if (!isAllowedCommand(config.command)) {
            return CodexTaskResult.disabled('Codex command is not allowed by configuration')
        }

        CodexWorkspace workspace = resolveWorkspace(config, command)
        if (!workspace.valid) {
            return CodexTaskResult.failed(workspace.message)
        }

        List<String> args = buildCommand(config, command)
        ProcessBuilder builder = new ProcessBuilder(args)
        builder.directory(workspace.path.toFile())
        builder.redirectErrorStream(true)

        try {
            Process process = builder.start()
            boolean exited = process.waitFor(Math.max(1, config.timeoutSeconds), TimeUnit.SECONDS)
            if (!exited) {
                process.destroyForcibly()
                return CodexTaskResult.failed("Codex task timed out after ${config.timeoutSeconds}s")
            }
            String output = readOutput(process)
            if (process.exitValue() == 0) {
                return CodexTaskResult.completed(output)
            }
            return CodexTaskResult.failed("Codex exited with ${process.exitValue()}\n${output}".trim())
        } catch (Exception ex) {
            log.warn('Codex task {} failed: {}', command.taskId, ex.message, ex)
            return CodexTaskResult.failed(ex.message ?: ex.class.simpleName)
        }
    }

    private static boolean isAllowedCommand(String command) {
        if (!command) {
            return false
        }
        if (command == 'codex') {
            return true
        }
        Path path = Path.of(command).toAbsolutePath().normalize()
        return path.fileName?.toString()?.startsWith('codex') && Files.isExecutable(path)
    }

    private static CodexWorkspace resolveWorkspace(EvoForgeProperties.CodexTask config, DeviceCommandMessage command) {
        String requestedKey = workspaceKey(command)
        Map<String, String> workspaces = config.workspaces ?: [:]
        String projectKey = requestedKey ?: (config.defaultWorkspace ?: '').toString().trim()
        String configuredPath = projectKey ? workspaces[projectKey] : null
        if (projectKey && !configuredPath) {
            String source = requestedKey ? 'Codex workspace' : 'Default Codex workspace'
            return CodexWorkspace.invalid("${source} '${projectKey}' is not configured".toString())
        }

        Path path = Path.of(configuredPath ?: config.workingDirectory ?: '.').toAbsolutePath().normalize()
        if (!Files.isDirectory(path)) {
            return CodexWorkspace.invalid("Codex workspace directory does not exist: ${path}".toString())
        }
        return CodexWorkspace.valid(projectKey ?: 'legacy-working-directory', path)
    }

    private static String workspaceKey(DeviceCommandMessage command) {
        Object value = command.attributes?.projectKey ?: command.attributes?.workspaceKey ?: command.attributes?.project
        String key = value == null ? '' : value.toString().trim()
        return key
    }

    private static List<String> buildCommand(EvoForgeProperties.CodexTask config, DeviceCommandMessage command) {
        List<String> args = [config.command ?: 'codex']
        if (config.extraArgs) {
            args.addAll(config.extraArgs.findAll { it })
        }
        String prompt = command.text ?: ''
        if (config.promptArg) {
            args << config.promptArg
        }
        args << prompt
        return args
    }

    private static String readOutput(Process process) {
        String output = process.inputStream.getText(StandardCharsets.UTF_8.name())
        int maxLength = 20000
        return output.length() > maxLength ? output.take(maxLength) + '\n[truncated]' : output
    }
}

class CodexWorkspace {
    boolean valid
    String key
    Path path
    String message

    static CodexWorkspace valid(String key, Path path) {
        return new CodexWorkspace(valid: true, key: key, path: path)
    }

    static CodexWorkspace invalid(String message) {
        return new CodexWorkspace(valid: false, message: message)
    }
}

class CodexTaskResult {
    String status
    String output
    String message
    boolean recoverable

    static CodexTaskResult completed(String output) {
        return new CodexTaskResult(
            status: DeviceProtocol.STATUS_COMPLETED,
            output: output,
            message: 'Codex task completed',
            recoverable: false
        )
    }

    static CodexTaskResult failed(String message) {
        return new CodexTaskResult(
            status: DeviceProtocol.STATUS_FAILED,
            message: message,
            recoverable: true
        )
    }

    static CodexTaskResult disabled(String message) {
        return new CodexTaskResult(
            status: DeviceProtocol.STATUS_FAILED,
            message: message,
            recoverable: true
        )
    }
}
