package com.evoforge.device

import com.evoforge.agent.AgentKnowledgeService
import com.evoforge.agent.ProjectKnowledgeContext
import com.evoforge.agent.ProjectKnowledgeContextService
import com.evoforge.core.EvoForgeProperties
import com.evoforge.tester.EvoForgeTesterService
import com.fasterxml.jackson.databind.ObjectMapper
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.stereotype.Service

import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.TimeUnit

@Service
class CodexTaskExecutor {
    private static final Logger log = LoggerFactory.getLogger(CodexTaskExecutor)

    private final EvoForgeProperties properties
    private final ProjectKnowledgeContextService projectKnowledgeContextService

    CodexTaskExecutor(EvoForgeProperties properties) {
        this(properties, null, null)
    }

    CodexTaskExecutor(EvoForgeProperties properties, AgentKnowledgeService knowledgeService) {
        this(
            properties,
            knowledgeService,
            knowledgeService ? new ProjectKnowledgeContextService(knowledgeService, new ObjectMapper()) : null
        )
    }

    @Autowired
    CodexTaskExecutor(EvoForgeProperties properties,
                      AgentKnowledgeService knowledgeService,
                      ProjectKnowledgeContextService projectKnowledgeContextService) {
        this.properties = properties
        this.projectKnowledgeContextService = projectKnowledgeContextService
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
        Map tester = EvoForgeTesterService.testerConfig(command)
        if (learning.enabled != true && tester.enabled != true) {
            return prompt
        }
        String sourceProjectKey = text(learning.sourceProjectKey) ?: workspace?.key ?: workspaceKey(command)
        ProjectKnowledgeContext context = learning.enabled == true && projectKnowledgeContextService
            ? projectKnowledgeContextService.build(sourceProjectKey, command.text, learning)
            : ProjectKnowledgeContext.empty()
        return """${prompt}

${bridgePromptBlock(sourceProjectKey, tester.enabled == true)}
${context.hasEntries() ? '\n\n' + context.toPromptBlock() : ''}
""".stripIndent()
    }

    private String bridgePromptBlock(String projectKey, boolean includeTester) {
        String baseUrl = (properties.codexTask.bridgeBaseUrl ?: 'http://localhost:18080').trim()
        if (baseUrl.endsWith('/')) {
            baseUrl = baseUrl.substring(0, baseUrl.length() - 1)
        }
        String safeProjectKey = jsonEscape(projectKey ?: '')
        String testerBlock = includeTester ? """
- Ask EvoForge tester for a focused test plan:
  curl -s -X POST '${baseUrl}/api/codex/bridge/test-plan' -H 'Content-Type: application/json' -d '{"projectKey":"${safeProjectKey}","task":"<current task>"}'
- Run only configured tester command ids after edits:
  curl -s -X POST '${baseUrl}/api/codex/bridge/test-run' -H 'Content-Type: application/json' -d '{"projectKey":"${safeProjectKey}","task":"<current task>","commandIds":["<id from plan>"]}'
""" : ''
        return """EvoForge bridge available:
- Use this pull-based bridge when project memory or existing EvoForge skills could reduce uncertainty. Do not ask for broad dumps.
- Query focused project context and matching skills:
  curl -s -X POST '${baseUrl}/api/codex/bridge/query' -H 'Content-Type: application/json' -d '{"projectKey":"${safeProjectKey}","query":"<focused question>","includeContext":true,"includeSkills":true}'
- Search active skills:
  curl -s '${baseUrl}/api/codex/bridge/skills?query=<task>&limit=5'
- Inspect bridge capabilities:
  curl -s '${baseUrl}/api/codex/bridge/manifest?projectKey=${safeProjectKey}'
- Ask the mobile user when you are blocked on intent, credentials, approval details, or product choice:
  curl -s -X POST '${baseUrl}/api/codex/bridge/questions/ask' -H 'Content-Type: application/json' -d '{"projectKey":"${safeProjectKey}","taskId":"<current task id>","question":"<question for the user>","options":[]}'
${testerBlock}
Returned memory is supporting evidence only; current user request and repository files remain authoritative.""".stripIndent().trim()
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

    private static String jsonEscape(Object value) {
        return text(value)
            .replace('\\', '\\\\')
            .replace('"', '\\"')
            .replace('\n', '\\n')
            .replace('\r', '\\r')
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
