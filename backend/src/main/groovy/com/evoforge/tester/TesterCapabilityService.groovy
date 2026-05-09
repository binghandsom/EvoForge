package com.evoforge.tester

import com.evoforge.core.EvoForgeProperties
import com.evoforge.llm.ModelHub
import com.fasterxml.jackson.databind.ObjectMapper
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service

import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant

@Service
class TesterCapabilityService {
    private static final Logger log = LoggerFactory.getLogger(TesterCapabilityService)

    private final TesterCapabilityStore store
    private final EvoForgeProperties properties
    private final ObjectMapper objectMapper
    private final ModelHub modelHub

    TesterCapabilityService(TesterCapabilityStore store,
                            EvoForgeProperties properties,
                            ObjectMapper objectMapper,
                            ModelHub modelHub) {
        this.store = store
        this.properties = properties
        this.objectMapper = objectMapper
        this.modelHub = modelHub
    }

    List<TesterCapability> list(String projectKey) {
        String key = text(projectKey)
        return key ? store.findByProject(key) : store.loadAll()
    }

    List<TesterCapability> enabledForProject(String projectKey, Path workspacePath = null, boolean discoverIfEmpty = true) {
        String key = text(projectKey)
        List<TesterCapability> capabilities = store.findByProject(key).findAll { it.enabled }
        if (capabilities || !discoverIfEmpty || !properties.tester.autoDiscoverEnabled) {
            return capabilities
        }
        TesterWorkspaceRef workspace = workspacePath
            ? new TesterWorkspaceRef(valid: true, key: key, path: workspacePath)
            : resolveWorkspace(key)
        if (!workspace.valid) {
            return fallbackCapabilities(key)
        }
        return discoverAndSave(key, workspace.path, properties.tester.modelDiscoveryEnabled).findAll { it.enabled } ?: fallbackCapabilities(key)
    }

    Map<String, Object> discover(Map request) {
        TesterWorkspaceRef workspace = resolveWorkspace(text(request?.projectKey))
        if (!workspace.valid) {
            return [
                projectKey: workspace.key,
                discovered: 0,
                saved     : [],
                reason    : workspace.message
            ] as Map<String, Object>
        }
        boolean useModel = request?.useModel == true || properties.tester.modelDiscoveryEnabled
        List<TesterCapability> saved = discoverAndSave(workspace.key, workspace.path, useModel)
        return [
            projectKey: workspace.key,
            workspace : workspace.path.toString(),
            source    : useModel ? 'template-and-model' : 'template',
            discovered: saved.size(),
            saved     : saved.collect { it.toView() }
        ] as Map<String, Object>
    }

    TesterCapability save(Map request) {
        String projectKey = required(request?.projectKey, 'projectKey')
        TesterCapability existing = store.findByProjectAndId(projectKey, text(request?.id)).orElse(null)
        TesterCapability capability = existing ?: new TesterCapability(
            recordId: text(request?.recordId) ?: UUID.randomUUID().toString(),
            projectKey: projectKey,
            source: text(request?.source) ?: 'manual'
        )
        capability.projectKey = projectKey
        capability.id = slug(required(request?.id ?: capability.id, 'id'))
        capability.name = required(request?.name ?: capability.name ?: capability.id, 'name')
        capability.type = text(request?.type ?: capability.type)
        capability.workingDirectory = text(request?.workingDirectory ?: capability.workingDirectory)
        capability.command = required(request?.command ?: capability.command, 'command')
        if (request?.containsKey('enabled')) capability.enabled = request.enabled != false
        capability.timeoutSeconds = intValue(request?.timeoutSeconds, capability.timeoutSeconds)
        capability.reason = text(request?.reason ?: capability.reason)
        capability.covers = listValue(request?.covers ?: capability.covers)
        capability.tags = listValue(request?.tags ?: capability.tags)
        capability.cost = normalizeChoice(request?.cost ?: capability.cost, 'medium', ['low', 'medium', 'high'])
        capability.confidence = normalizeChoice(request?.confidence ?: capability.confidence, 'medium', ['low', 'medium', 'high'])
        capability.evidenceParser = text(request?.evidenceParser ?: capability.evidenceParser)
        capability.fallbackCommandIds = listValue(request?.fallbackCommandIds ?: capability.fallbackCommandIds)
        capability.repairScopes = listValue(request?.repairScopes ?: capability.repairScopes)
        capability.optimizationNotes = text(request?.optimizationNotes ?: capability.optimizationNotes)
        capability.metadata = request?.metadata instanceof Map ? request.metadata as Map<String, Object> : capability.metadata ?: [:]
        capability.source = 'manual'
        capability.updatedAt = Instant.now()
        validateCapability(capability)
        return store.save(capability)
    }

    void delete(String projectKey, String id) {
        store.delete(required(projectKey, 'projectKey'), required(id, 'id'))
    }

    void recordRunResults(String projectKey, List<TesterCommandResult> results) {
        if (!properties.tester.autoOptimizeEnabled || !projectKey || !results) {
            return
        }
        results.each { result ->
            TesterCapability capability = store.findByProjectAndId(projectKey, result.id).orElse(null)
            if (!capability) {
                return
            }
            boolean passed = result.status == com.evoforge.device.DeviceProtocol.STATUS_COMPLETED
            capability.successCount += passed ? 1 : 0
            capability.failureCount += passed ? 0 : 1
            capability.lastStatus = result.status ?: ''
            capability.lastExitCode = result.exitCode
            capability.lastDurationMs = result.durationMs
            capability.lastOutputExcerpt = compact(result.output ?: result.message, 1200)
            capability.lastRunAt = Instant.now()
            capability.optimizationNotes = optimizationNote(capability, result, passed)
            if (passed && capability.successCount >= 2 && capability.confidence != 'high') {
                capability.confidence = 'high'
            }
            if (!passed && result.timedOut) {
                capability.cost = 'high'
                capability.confidence = 'low'
            }
            store.save(capability)
        }
    }

    private List<TesterCapability> discoverAndSave(String projectKey, Path workspace, boolean useModel) {
        List<TesterCapability> templates = discoverTemplates(projectKey, workspace)
        List<TesterCapability> candidates = useModel ? mergeModelSuggestions(projectKey, workspace, templates) : templates
        List<TesterCapability> saved = []
        candidates.each { candidate ->
            if (!candidate.id || !candidate.command || !capabilityLooksRunnable(candidate, workspace)) {
                return
            }
            TesterCapability existing = store.findByProjectAndId(projectKey, candidate.id).orElse(null)
            if (existing) {
                if (existing.source == 'manual') {
                    saved << existing
                    return
                }
                candidate.recordId = existing.recordId
                candidate.createdAt = existing.createdAt
                candidate.successCount = existing.successCount
                candidate.failureCount = existing.failureCount
                candidate.lastStatus = existing.lastStatus
                candidate.lastExitCode = existing.lastExitCode
                candidate.lastDurationMs = existing.lastDurationMs
                candidate.lastOutputExcerpt = existing.lastOutputExcerpt
                candidate.lastRunAt = existing.lastRunAt
                candidate.enabled = existing.enabled
            }
            saved << store.save(candidate)
        }
        return saved.sort { a, b -> a.id <=> b.id }
    }

    private void validateCapability(TesterCapability capability) {
        TesterWorkspaceRef workspace = resolveWorkspace(capability.projectKey)
        if (!workspace.valid) {
            throw new IllegalArgumentException(workspace.message)
        }
        if (!capabilityLooksRunnable(capability, workspace.path)) {
            throw new IllegalArgumentException('Tester capability command is empty, or workingDirectory/executable path is outside this project workspace')
        }
    }

    private boolean capabilityLooksRunnable(TesterCapability capability, Path workspace) {
        List<String> tokens = tokenize(capability.command)
        if (!tokens) {
            return false
        }
        Path workingDirectory = resolveWorkingDirectory(workspace, capability.workingDirectory)
        if (!Files.isDirectory(workingDirectory) || !isInside(workspace, workingDirectory)) {
            return false
        }
        String executable = tokens.first()
        if (executable.contains('/') || executable.contains('\\')) {
            Path executablePath = workingDirectory.resolve(executable).normalize()
            return isInside(workspace, executablePath) && Files.isExecutable(executablePath)
        }
        return true
    }

    private List<TesterCapability> discoverTemplates(String projectKey, Path workspace) {
        List<TesterCapability> capabilities = []
        if (Files.exists(workspace.resolve('backend/pom.xml'))) {
            capabilities << capability(projectKey, 'backend-tests', 'Backend Tests', 'backend-regression', 'backend', 'mvn test', ['backend'], ['maven', 'groovy', 'regression'], 'medium', 'high', 'jvm-test', 'Spring/Groovy backend regression tests discovered from backend/pom.xml')
        } else if (Files.exists(workspace.resolve('pom.xml'))) {
            capabilities << capability(projectKey, 'jvm-tests', 'JVM Tests', 'backend-regression', '.', 'mvn test', ['backend'], ['maven', 'regression'], 'medium', 'high', 'jvm-test', 'Maven regression tests discovered from pom.xml')
        }
        if (Files.exists(workspace.resolve('frontend/pubspec.yaml'))) {
            capabilities << capability(projectKey, 'frontend-analyze', 'Frontend Analyze', 'static-analysis', 'frontend', 'flutter analyze', ['frontend'], ['flutter', 'dart', 'analysis'], 'low', 'high', 'flutter-test', 'Flutter analyzer discovered from frontend/pubspec.yaml')
            capabilities << capability(projectKey, 'frontend-tests', 'Frontend Tests', 'ui-regression', 'frontend', 'flutter test', ['frontend'], ['flutter', 'dart', 'regression'], 'medium', 'high', 'flutter-test', 'Flutter tests discovered from frontend/pubspec.yaml')
            capabilities << capability(projectKey, 'frontend-web-build', 'Frontend Web Build', 'build', 'frontend', 'flutter build web', ['frontend'], ['flutter', 'web', 'build'], 'high', 'high', 'flutter-test', 'Flutter web build verifies release artifacts')
        } else if (Files.exists(workspace.resolve('pubspec.yaml'))) {
            capabilities << capability(projectKey, 'flutter-analyze', 'Flutter Analyze', 'static-analysis', '.', 'flutter analyze', ['frontend'], ['flutter', 'dart', 'analysis'], 'low', 'high', 'flutter-test', 'Flutter analyzer discovered from pubspec.yaml')
            capabilities << capability(projectKey, 'flutter-tests', 'Flutter Tests', 'ui-regression', '.', 'flutter test', ['frontend'], ['flutter', 'dart', 'regression'], 'medium', 'high', 'flutter-test', 'Flutter tests discovered from pubspec.yaml')
        }
        discoverNodeScripts(projectKey, workspace, capabilities)
        if (Files.exists(workspace.resolve('go.mod'))) {
            capabilities << capability(projectKey, 'go-tests', 'Go Tests', 'backend-regression', '.', 'go test ./...', ['backend'], ['go', 'regression'], 'medium', 'high', 'generic', 'Go tests discovered from go.mod')
        }
        if (Files.exists(workspace.resolve('pytest.ini')) || Files.exists(workspace.resolve('pyproject.toml'))) {
            capabilities << capability(projectKey, 'python-tests', 'Python Tests', 'backend-regression', '.', 'pytest', ['backend'], ['python', 'pytest'], 'medium', 'high', 'pytest', 'Pytest capability discovered from Python project metadata')
        }
        return capabilities.unique { it.id }
    }

    private void discoverNodeScripts(String projectKey, Path workspace, List<TesterCapability> capabilities) {
        [['.', workspace.resolve('package.json')], ['frontend', workspace.resolve('frontend/package.json')]].each { pair ->
            String dir = pair[0] as String
            Path packageJson = pair[1] as Path
            if (!Files.exists(packageJson)) {
                return
            }
            try {
                Map json = objectMapper.readValue(packageJson.toFile(), Map)
                Map scripts = json.scripts instanceof Map ? json.scripts as Map : [:]
                if (scripts.test) {
                    capabilities << capability(projectKey, "${dir == '.' ? 'node' : dir}-tests".toString(), 'Node Tests', 'ui-regression', dir, 'npm test', ['frontend'], ['node', 'regression'], 'medium', 'medium', 'node-test', 'npm test discovered from package.json')
                }
                if (scripts.lint) {
                    capabilities << capability(projectKey, "${dir == '.' ? 'node' : dir}-lint".toString(), 'Node Lint', 'static-analysis', dir, 'npm run lint', ['frontend'], ['node', 'lint'], 'low', 'medium', 'node-test', 'npm run lint discovered from package.json')
                }
            } catch (Exception ex) {
                log.debug('Failed to inspect package.json {}: {}', packageJson, ex.message)
            }
        }
    }

    private List<TesterCapability> mergeModelSuggestions(String projectKey, Path workspace, List<TesterCapability> templates) {
        if (!modelHub) {
            return templates
        }
        try {
            String prompt = """You are generating EvoForge tester capability records.
Return strict JSON only: {"capabilities":[...]}.
Use these conventional candidate commands as project-derived examples; prefer commands that can genuinely test this workspace:
${objectMapper.writeValueAsString(templates.collect { it.toView() })}

Project key: ${projectKey}
Workspace summary:
${workspaceSummary(workspace)}

You may rename, tag, rank cost/confidence, or add a capability when the working directory exists and the command is a reasonable tester action for this project. Fields: id,name,type,workingDirectory,command,covers,tags,cost,confidence,evidenceParser,reason.
""".stripIndent()
            String output = modelHub.getLlm(null).chat(prompt, [purpose: 'tester-capability-discovery'] as Map<String, Object>)
            Map parsed = objectMapper.readValue(extractJson(output), Map)
            List<TesterCapability> suggested = (parsed.capabilities instanceof Collection ? parsed.capabilities : []).collect { raw ->
                capabilityFromMap(projectKey, raw instanceof Map ? raw as Map : [:], 'model-discovered')
            }.findAll { it?.id && it?.command && capabilityLooksRunnable(it, workspace) }
            Map<String, TesterCapability> merged = templates.collectEntries { [(it.id): it] }
            suggested.each { item -> merged[item.id] = item }
            return merged.values().toList()
        } catch (Exception ex) {
            log.debug('Tester model discovery fell back to templates: {}', ex.message)
            return templates
        }
    }

    private TesterCapability capabilityFromMap(String projectKey, Map raw, String source) {
        return new TesterCapability(
            recordId: UUID.randomUUID().toString(),
            projectKey: projectKey,
            id: slug(text(raw.id)),
            name: text(raw.name) ?: text(raw.id),
            type: text(raw.type),
            workingDirectory: text(raw.workingDirectory),
            command: text(raw.command),
            enabled: raw.enabled != false,
            timeoutSeconds: intValue(raw.timeoutSeconds, 0),
            reason: text(raw.reason),
            covers: listValue(raw.covers),
            tags: listValue(raw.tags),
            cost: normalizeChoice(raw.cost, 'medium', ['low', 'medium', 'high']),
            confidence: normalizeChoice(raw.confidence, 'medium', ['low', 'medium', 'high']),
            evidenceParser: text(raw.evidenceParser),
            fallbackCommandIds: listValue(raw.fallbackCommandIds),
            repairScopes: listValue(raw.repairScopes),
            source: source,
            metadata: [discoveredAt: Instant.now().toString()]
        )
    }

    private TesterCapability capability(String projectKey,
                                        String id,
                                        String name,
                                        String type,
                                        String workingDirectory,
                                        String command,
                                        List<String> covers,
                                        List<String> tags,
                                        String cost,
                                        String confidence,
                                        String evidenceParser,
                                        String reason) {
        return new TesterCapability(
            recordId: UUID.randomUUID().toString(),
            projectKey: projectKey,
            id: id,
            name: name,
            type: type,
            workingDirectory: workingDirectory,
            command: command,
            enabled: true,
            reason: reason,
            covers: covers,
            tags: tags,
            cost: cost,
            confidence: confidence,
            evidenceParser: evidenceParser,
            source: 'template-discovered',
            metadata: [discoveredAt: Instant.now().toString()]
        )
    }

    private TesterWorkspaceRef resolveWorkspace(String requestedKey) {
        Map<String, String> workspaces = properties.codexTask.workspaces ?: [:]
        String key = text(requestedKey) ?: text(properties.codexTask.defaultWorkspace)
        String configuredPath = key ? workspaces[key] : null
        if (key && !configuredPath) {
            return TesterWorkspaceRef.invalid(key, "Tester workspace '${key}' is not configured".toString())
        }
        Path path = Path.of(configuredPath ?: properties.codexTask.workingDirectory ?: '.').toAbsolutePath().normalize()
        if (!Files.isDirectory(path)) {
            return TesterWorkspaceRef.invalid(key ?: 'legacy-working-directory', "Tester workspace directory does not exist: ${path}".toString())
        }
        return new TesterWorkspaceRef(valid: true, key: key ?: 'legacy-working-directory', path: path)
    }

    private List<TesterCapability> fallbackCapabilities(String projectKey) {
        List<EvoForgeProperties.TesterCommand> raw = []
        if (projectKey && properties.tester.projectCommands?.containsKey(projectKey)) {
            raw = properties.tester.projectCommands[projectKey] ?: []
        } else {
            raw = properties.tester.defaultCommands ?: []
        }
        return raw.findAll { it?.enabled != false }.collect { command ->
            capabilityFromCommand(projectKey, command)
        }
    }

    private static TesterCapability capabilityFromCommand(String projectKey, EvoForgeProperties.TesterCommand command) {
        return new TesterCapability(
            recordId: UUID.randomUUID().toString(),
            projectKey: projectKey,
            id: text(command.id) ?: slug(text(command.name) ?: text(command.command)),
            name: text(command.name) ?: text(command.id) ?: text(command.command),
            type: text(command.type),
            workingDirectory: text(command.workingDirectory),
            command: text(command.command),
            enabled: command.enabled,
            timeoutSeconds: command.timeoutSeconds,
            reason: text(command.reason),
            covers: listValue(command.covers),
            tags: listValue(command.tags),
            cost: text(command.cost),
            confidence: text(command.confidence),
            evidenceParser: text(command.evidenceParser),
            fallbackCommandIds: listValue(command.fallbackCommandIds),
            repairScopes: listValue(command.repairScopes),
            source: 'legacy-config'
        )
    }

    private static List<String> tokenize(String value) {
        return text(value).split('\\s+').findAll { it }
    }

    private static Path resolveWorkingDirectory(Path workspace, Object raw) {
        String value = text(raw)
        Path path = value ? Path.of(value) : Path.of('.')
        return path.isAbsolute() ? path.normalize() : workspace.resolve(path).normalize()
    }

    private static boolean isInside(Path parent, Path child) {
        return child.toAbsolutePath().normalize().startsWith(parent.toAbsolutePath().normalize())
    }

    private static String workspaceSummary(Path workspace) {
        try {
            return Files.list(workspace)
                .limit(80)
                .collect { path -> Files.isDirectory(path) ? "${path.fileName}/" : path.fileName.toString() }
                .sorted()
                .join('\n')
        } catch (Exception ignored) {
            return workspace.toString()
        }
    }

    private static String extractJson(String output) {
        String text = text(output)
        int start = text.indexOf('{')
        int end = text.lastIndexOf('}')
        if (start >= 0 && end > start) {
            return text.substring(start, end + 1)
        }
        return text
    }

    private static String optimizationNote(TesterCapability capability, TesterCommandResult result, boolean passed) {
        if (passed) {
            return "Last run passed; success=${capability.successCount}, failure=${capability.failureCount}".toString()
        }
        if (result.timedOut) {
            return 'Last run timed out; marked as high cost and low confidence until edited or passing again.'
        }
        return "Last run failed; review evidence before changing confidence. success=${capability.successCount}, failure=${capability.failureCount}".toString()
    }

    private static String required(Object value, String field) {
        String text = text(value)
        if (!text) {
            throw new IllegalArgumentException("${field} is required")
        }
        return text
    }

    private static int intValue(Object value, int fallback) {
        try {
            return value == null || value.toString().trim().isEmpty() ? fallback : value.toString().toInteger()
        } catch (Exception ignored) {
            return fallback
        }
    }

    private static String normalizeChoice(Object raw, String fallback, List<String> allowed) {
        String value = text(raw).toLowerCase(Locale.ROOT)
        return allowed.contains(value) ? value : fallback
    }

    private static List<String> listValue(Object value) {
        if (value instanceof Collection) {
            return value.collect { text(it) }.findAll { it }.unique()
        }
        return text(value).split(',').collect { text(it) }.findAll { it }.unique()
    }

    private static String slug(String value) {
        return text(value).toLowerCase(Locale.ROOT)
            .replaceAll('[^a-z0-9\\u4e00-\\u9fff._-]+', '-')
            .replaceAll('^-+|-+$', '')
            .take(80)
    }

    private static String compact(Object value, int limit) {
        String normalized = (value ?: '').toString().trim()
        return normalized.length() > limit ? normalized.take(limit) + '\n[truncated]' : normalized
    }

    private static String text(Object value) {
        return value == null ? '' : value.toString().trim()
    }
}

class TesterWorkspaceRef {
    boolean valid
    String key
    Path path
    String message

    static TesterWorkspaceRef invalid(String key, String message) {
        return new TesterWorkspaceRef(valid: false, key: key, message: message)
    }
}
