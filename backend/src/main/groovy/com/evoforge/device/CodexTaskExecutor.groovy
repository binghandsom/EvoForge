package com.evoforge.device

import com.evoforge.agent.AgentKnowledgeFact
import com.evoforge.agent.AgentKnowledgeService
import com.evoforge.core.EvoForgeProperties
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.stereotype.Service

import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.util.Locale
import java.util.concurrent.TimeUnit

@Service
class CodexTaskExecutor {
    private static final Logger log = LoggerFactory.getLogger(CodexTaskExecutor)

    private final EvoForgeProperties properties
    private final AgentKnowledgeService knowledgeService

    CodexTaskExecutor(EvoForgeProperties properties) {
        this(properties, null)
    }

    @Autowired
    CodexTaskExecutor(EvoForgeProperties properties, AgentKnowledgeService knowledgeService) {
        this.properties = properties
        this.knowledgeService = knowledgeService
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

        List<String> args = buildCommand(config, command, workspace)
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

    private List<String> buildCommand(EvoForgeProperties.CodexTask config, DeviceCommandMessage command, CodexWorkspace workspace) {
        List<String> args = [config.command ?: 'codex']
        if (config.extraArgs) {
            args.addAll(config.extraArgs.findAll { it })
        }
        String prompt = enrichedPrompt(command, workspace)
        if (config.promptArg) {
            args << config.promptArg
        }
        args << prompt
        return args
    }

    private String enrichedPrompt(DeviceCommandMessage command, CodexWorkspace workspace) {
        String prompt = command.text ?: ''
        Map learning = learningConfig(command)
        if (learning.enabled != true || !knowledgeService) {
            return prompt
        }
        String sourceProjectKey = text(learning.sourceProjectKey) ?: workspace?.key ?: workspaceKey(command)
        String query = [
            sourceProjectKey,
            command.text,
            'change-lineage codex-output project-facts skills architecture errors'
        ].findAll { it }.join(' ')
        List<AgentKnowledgeFact> facts = knowledgeService.search(query, 6)
            .findAll { fact -> !sourceProjectKey || fact.scope == "project:${sourceProjectKey}".toString() || (fact.tags ?: []).contains(sourceProjectKey) }
            .take(6)
        if (!facts) {
            return prompt
        }
        String context = facts.collect { fact ->
            "- ${fact.key} (${fact.source}, confidence ${String.format(Locale.ROOT, '%.2f', fact.confidence)}): ${compact(fact.value, 900)}"
        }.join('\n')
        return """${prompt}

EvoForge project memory for ${sourceProjectKey ?: 'current project'}:
${context}

Use this memory as supporting context. If it conflicts with the current task or repository evidence, verify and prefer the current repository.
""".stripIndent()
    }

    private static Map learningConfig(DeviceCommandMessage command) {
        Object raw = command.attributes?.evoforgeLearning
        if (raw instanceof Map) {
            Map config = new LinkedHashMap(raw as Map)
            config.enabled = config.enabled == true
            return config
        }
        return [enabled: raw == true || command.attributes?.learnWithEvoForge == true]
    }

    private static String text(Object value) {
        String result = value == null ? '' : value.toString().trim()
        return result
    }

    private static String compact(Object value, int limit) {
        String normalized = (value ?: '').toString().replaceAll('\\s+', ' ').trim()
        return normalized.length() > limit ? normalized.take(limit) + '...' : normalized
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
