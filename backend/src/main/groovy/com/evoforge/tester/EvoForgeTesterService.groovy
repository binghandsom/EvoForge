package com.evoforge.tester

import com.evoforge.agent.AgentKnowledgeFact
import com.evoforge.agent.AgentKnowledgeService
import com.evoforge.agent.ProjectKnowledgeContext
import com.evoforge.agent.ProjectKnowledgeContextService
import com.evoforge.core.EvoForgeProperties
import com.evoforge.device.CodexTaskResult
import com.evoforge.device.DeviceCommandMessage
import com.evoforge.device.DeviceProtocol
import com.fasterxml.jackson.databind.ObjectMapper
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service

import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration
import java.time.Instant
import java.util.concurrent.TimeUnit

@Service
class EvoForgeTesterService {
    private static final Logger log = LoggerFactory.getLogger(EvoForgeTesterService)

    private final EvoForgeProperties properties
    private final AgentKnowledgeService knowledgeService
    private final ProjectKnowledgeContextService contextService
    private final ObjectMapper objectMapper
    private final TesterCapabilityService capabilityService

    EvoForgeTesterService(EvoForgeProperties properties,
                          AgentKnowledgeService knowledgeService,
                          ProjectKnowledgeContextService contextService,
                          ObjectMapper objectMapper,
                          TesterCapabilityService capabilityService) {
        this.properties = properties
        this.knowledgeService = knowledgeService
        this.contextService = contextService
        this.objectMapper = objectMapper
        this.capabilityService = capabilityService
    }

    Map<String, Object> capability(String baseUrl, String projectKey) {
        String safeBaseUrl = normalizeBaseUrl(baseUrl)
        String safeProjectKey = text(projectKey) ?: '<projectKey>'
        return [
            enabled       : properties.tester.enabled,
            planEndpoint  : "${safeBaseUrl}/api/codex/bridge/test-plan".toString(),
            runEndpoint   : "${safeBaseUrl}/api/codex/bridge/test-run".toString(),
            retrievalUsage: 'Ask for a quality plan first, choose a route or configured capability ids, then inspect evidence packets if something fails.',
            commandSource : 'database-quality-capabilities',
            autoRepair    : [
                enabled: properties.tester.autoRepairEnabled,
                maxAttempts: properties.tester.maxRepairAttempts,
                mode: properties.tester.autoRepairEnabled ? 'opt-in-codex-repair-loop' : 'evidence-packet-only'
            ],
            commandTypes  : [DeviceProtocol.TYPE_TESTER_TASK],
            suggestedCalls: [
                [
                    purpose: 'Ask EvoForge which tests are available for this task',
                    command: """curl -s -X POST '${safeBaseUrl}/api/codex/bridge/test-plan' -H 'Content-Type: application/json' -d '{"projectKey":"${safeProjectKey}","task":"<current task>"}'"""
                ],
                [
                    purpose: 'Run a recommended quality route after edits',
                    command: """curl -s -X POST '${safeBaseUrl}/api/codex/bridge/test-run' -H 'Content-Type: application/json' -d '{"projectKey":"${safeProjectKey}","task":"<current task>","routeId":"recommended"}'"""
                ]
            ]
        ] as Map<String, Object>
    }

    Map<String, Object> plan(Map request, String baseUrl = '') {
        TesterWorkspace workspace = resolveWorkspace(projectKey(request))
        String task = taskText(request)
        List<ResolvedTesterCommand> commands = workspace.valid ? configuredCommands(workspace, commandIds(request)) : []
        ProjectKnowledgeContext context = workspace.valid && contextService
            ? contextService.build(workspace.key, task, contextRequest(request))
            : ProjectKnowledgeContext.empty()
        Map<String, Object> riskProfile = buildRiskProfile(request, context)
        List<Map<String, Object>> routes = buildRoutes(commands, riskProfile)
        Map<String, Object> recommendedRoute = recommendedRoute(routes)

        return [
            tester     : 'evoforge-tester',
            enabled    : properties.tester.enabled,
            autoRepair : [
                enabled: properties.tester.autoRepairEnabled,
                maxAttempts: properties.tester.maxRepairAttempts,
                mode: properties.tester.autoRepairEnabled ? 'opt-in-codex-repair-loop' : 'evidence-packet-only'
            ],
            projectKey : workspace.key,
            workspace  : workspace.path?.toString(),
            canRun     : properties.tester.enabled && workspace.valid && !commands.isEmpty(),
            reason     : workspace.valid ? (!commands.isEmpty() ? 'project quality capabilities available' : 'no tester capabilities discovered or configured for project') : workspace.message,
            riskProfile: riskProfile,
            routes     : routes,
            recommendedRoute: recommendedRoute,
            capabilities: commands.collect { commandCard(it) },
            commands   : commands.collect { commandCard(it) },
            context    : [
                count      : context.entries?.size() ?: 0,
                promptBlock: context.toPromptBlock(),
                entries    : context.entries.collect { entry ->
                    [
                        key       : entry.key,
                        source    : entry.source,
                        confidence: entry.confidence,
                        score     : entry.score,
                        reason    : entry.reason,
                        excerpt   : entry.excerpt
                    ]
                }
            ],
            runEndpoint: baseUrl ? "${normalizeBaseUrl(baseUrl)}/api/codex/bridge/test-run".toString() : '/api/codex/bridge/test-run',
            usageHint  : 'Use routeId or commandIds from this plan; EvoForge will not execute arbitrary commands from the request.'
        ].findAll { it.value != null } as Map<String, Object>
    }

    TesterRunResult run(DeviceCommandMessage command, Closure progress = null) {
        return run(requestFromCommand(command, null), progress)
    }

    TesterRunResult runAfterCodex(DeviceCommandMessage command,
                                  CodexTaskResult codexResult,
                                  Closure progress = null) {
        return run(requestFromCommand(command, codexResult), progress)
    }

    TesterRunResult run(Map request, Closure progress = null) {
        Instant startedAt = Instant.now()
        TesterWorkspace workspace = resolveWorkspace(projectKey(request))
        if (!properties.tester.enabled) {
            return failedRun(request, workspace, 'EvoForge tester is disabled', startedAt)
        }
        if (!workspace.valid) {
            return failedRun(request, workspace, workspace.message, startedAt)
        }

        String task = taskText(request)
        ProjectKnowledgeContext context = contextService
            ? contextService.build(workspace.key, task, contextRequest(request))
            : ProjectKnowledgeContext.empty()
        Map<String, Object> riskProfile = buildRiskProfile(request, context)
        List<Map<String, Object>> routes = buildRoutes(configuredCommands(workspace, []), riskProfile)
        Map<String, Object> selectedRoute = selectRoute(routes, request)
        List<ResolvedTesterCommand> commands = commandsForRun(workspace, request, selectedRoute)
        if (commands.isEmpty()) {
            return failedRun(request, workspace, 'No tester capabilities are configured for this project, route, or commandIds filter', startedAt)
        }

        List<TesterCommandResult> results = []
        commands.each { ResolvedTesterCommand command ->
            progress?.call([
                phase     : 'command_started',
                commandId : command.id,
                name      : command.name,
                capability: commandCard(command),
                command   : command.displayCommand,
                workingDir: relativePath(workspace.path, command.workingDirectory),
                message   : "Tester command started: ${command.name}".toString()
            ] as Map<String, Object>)

            TesterCommandResult result = executeCommand(command, workspace)
            results << result

            progress?.call([
                phase     : result.status == DeviceProtocol.STATUS_COMPLETED ? 'command_completed' : 'command_failed',
                commandId : command.id,
                name      : command.name,
                status    : result.status,
                exitCode  : result.exitCode,
                durationMs: result.durationMs,
                message   : result.message,
                evidence  : result.evidencePacket,
                output    : compact(result.output, 4000)
            ].findAll { it.value != null } as Map<String, Object>)
        }

        boolean passed = results.every { it.status == DeviceProtocol.STATUS_COMPLETED }
        Map<String, Object> evidenceSummary = buildEvidenceSummary(results, task, selectedRoute)
        TesterRunResult runResult = new TesterRunResult(
            status: passed ? DeviceProtocol.STATUS_COMPLETED : DeviceProtocol.STATUS_FAILED,
            message: passed ? "EvoForge tester passed ${results.size()} command(s)" : "EvoForge tester found failures in ${results.count { it.status != DeviceProtocol.STATUS_COMPLETED }} command(s)",
            output: outputSummary(results),
            recoverable: !passed,
            projectKey: workspace.key,
            workspace: workspace.path?.toString(),
            task: compact(task, 2000),
            trigger: text(request?.trigger) ?: 'tester',
            startedAt: startedAt,
            completedAt: Instant.now(),
            routeId: text(selectedRoute?.id),
            route: selectedRoute,
            riskProfile: riskProfile,
            evidenceSummary: evidenceSummary,
            repairPrompt: evidenceSummary.repairPrompt,
            commands: results
        )
        capabilityService?.recordRunResults(runResult.projectKey, results)
        recordKnowledge(request, runResult)
        return runResult
    }

    private TesterCommandResult executeCommand(ResolvedTesterCommand command, TesterWorkspace workspace) {
        Instant startedAt = Instant.now()
        ProcessBuilder builder = new ProcessBuilder(command.tokens)
        builder.directory(command.workingDirectory.toFile())
        builder.redirectErrorStream(true)

        try {
            Process process = builder.start()
            ByteArrayOutputStream buffer = new ByteArrayOutputStream()
            Thread reader = Thread.startDaemon("evoforge-tester-${command.id}") {
                process.inputStream.transferTo(buffer)
            }
            int timeout = Math.max(1, command.timeoutSeconds ?: properties.tester.timeoutSeconds)
            boolean exited = process.waitFor(timeout, TimeUnit.SECONDS)
            if (!exited) {
                process.destroyForcibly()
                reader.join(1000)
                TesterCommandResult result = new TesterCommandResult(
                    id: command.id,
                    name: command.name,
                    type: command.type,
                    covers: command.covers,
                    tags: command.tags,
                    cost: command.cost,
                    confidence: command.confidence,
                    evidenceParser: command.evidenceParser,
                    command: command.displayCommand,
                    workingDirectory: relativePath(workspace.path, command.workingDirectory),
                    status: DeviceProtocol.STATUS_FAILED,
                    exitCode: null,
                    timedOut: true,
                    durationMs: durationMs(startedAt),
                    output: compact(buffer.toString(StandardCharsets.UTF_8.name()), properties.tester.maxOutputChars),
                    message: "Tester command timed out after ${timeout}s"
                )
                result.evidencePacket = evidencePacket(result)
                return result
            }
            reader.join(1000)
            int exitCode = process.exitValue()
            String output = compact(buffer.toString(StandardCharsets.UTF_8.name()), properties.tester.maxOutputChars)
            TesterCommandResult result = new TesterCommandResult(
                id: command.id,
                name: command.name,
                type: command.type,
                covers: command.covers,
                tags: command.tags,
                cost: command.cost,
                confidence: command.confidence,
                evidenceParser: command.evidenceParser,
                command: command.displayCommand,
                workingDirectory: relativePath(workspace.path, command.workingDirectory),
                status: exitCode == 0 ? DeviceProtocol.STATUS_COMPLETED : DeviceProtocol.STATUS_FAILED,
                exitCode: exitCode,
                timedOut: false,
                durationMs: durationMs(startedAt),
                output: output,
                message: exitCode == 0 ? "Tester command passed: ${command.name}" : "Tester command failed with exit ${exitCode}: ${command.name}"
            )
            result.evidencePacket = evidencePacket(result)
            return result
        } catch (Exception ex) {
            log.warn('Tester command {} failed: {}', command.id, ex.message, ex)
            TesterCommandResult result = new TesterCommandResult(
                id: command.id,
                name: command.name,
                type: command.type,
                covers: command.covers,
                tags: command.tags,
                cost: command.cost,
                confidence: command.confidence,
                evidenceParser: command.evidenceParser,
                command: command.displayCommand,
                workingDirectory: relativePath(workspace.path, command.workingDirectory),
                status: DeviceProtocol.STATUS_FAILED,
                durationMs: durationMs(startedAt),
                output: '',
                message: ex.message ?: ex.class.simpleName
            )
            result.evidencePacket = evidencePacket(result)
            return result
        }
    }

    private List<ResolvedTesterCommand> configuredCommands(TesterWorkspace workspace, List<String> selectedIds) {
        List<EvoForgeProperties.TesterCommand> rawCommands = capabilityService
            ? capabilityService.enabledForProject(workspace.key, workspace.path, true).collect { it.toCommand() }
            : legacyCommands(workspace.key)
        List<String> selected = selectedIds.collect { text(it) }.findAll { it }
        return rawCommands
            .findAll { it?.enabled != false }
            .withIndex()
            .collect { entry -> resolveCommand(workspace, entry[0] as EvoForgeProperties.TesterCommand, entry[1] as int) }
            .findAll { it?.valid }
            .findAll { selected.isEmpty() || selected.contains(it.id) }
    }

    private List<EvoForgeProperties.TesterCommand> legacyCommands(String projectKey) {
        if (projectKey && properties.tester.projectCommands?.containsKey(projectKey)) {
            return properties.tester.projectCommands[projectKey] ?: []
        }
        return properties.tester.defaultCommands ?: []
    }

    private List<ResolvedTesterCommand> commandsForRun(TesterWorkspace workspace,
                                                       Map request,
                                                       Map<String, Object> selectedRoute) {
        List<String> selected = commandIds(request)
        if (selected.isEmpty()) {
            selected = routeCommandIds(selectedRoute)
        }
        return configuredCommands(workspace, selected)
    }

    private ResolvedTesterCommand resolveCommand(TesterWorkspace workspace,
                                                 EvoForgeProperties.TesterCommand config,
                                                 int index) {
        String display = text(config?.command)
        List<String> tokens = tokenize(display)
        if (!tokens) {
            return ResolvedTesterCommand.invalid('Tester command is empty')
        }

        Path workingDirectory = resolveWorkingDirectory(workspace.path, config?.workingDirectory)
        if (!workingDirectory || !Files.isDirectory(workingDirectory)) {
            return ResolvedTesterCommand.invalid("Tester working directory does not exist: ${config?.workingDirectory ?: '.'}".toString())
        }
        if (!isInside(workspace.path, workingDirectory)) {
            return ResolvedTesterCommand.invalid('Tester working directory must stay inside the configured workspace')
        }

        String executable = tokens.first()
        if (executable.contains('/') || executable.contains('\\')) {
            Path executablePath = workingDirectory.resolve(executable).normalize()
            if (!isInside(workspace.path, executablePath) || !Files.isExecutable(executablePath)) {
                return ResolvedTesterCommand.invalid("Tester executable is not available inside workspace: ${executable}".toString())
            }
        }

        String id = text(config?.id) ?: slug(text(config?.name) ?: display) ?: "test-${index + 1}".toString()
        String name = text(config?.name) ?: id
        return new ResolvedTesterCommand(
            valid: true,
            id: id,
            name: name,
            type: text(config?.type) ?: inferType(id, name, display, config?.workingDirectory),
            covers: normalizedList(config?.covers) ?: inferCovers(id, name, display, config?.workingDirectory),
            tags: normalizedList(config?.tags),
            cost: normalizeChoice(config?.cost, 'medium', ['low', 'medium', 'high']),
            confidence: normalizeChoice(config?.confidence, 'medium', ['low', 'medium', 'high']),
            evidenceParser: text(config?.evidenceParser) ?: inferEvidenceParser(display),
            fallbackCommandIds: normalizedList(config?.fallbackCommandIds),
            repairScopes: normalizedList(config?.repairScopes),
            tokens: tokens,
            displayCommand: display,
            workingDirectory: workingDirectory,
            timeoutSeconds: config?.timeoutSeconds ?: 0,
            reason: text(config?.reason)
        )
    }

    private Map<String, Object> buildRiskProfile(Map request, ProjectKnowledgeContext context) {
        String haystack = lower([
            taskText(request),
            normalizedList(request?.changedFiles ?: request?.files ?: request?.paths).join(' '),
            text(request?.codexOutput),
            text(request?.codexMessage),
            context?.entries?.collect { "${it.key} ${it.excerpt}" }?.join(' ')
        ].findAll { it }.join('\n'))
        List<Map<String, Object>> areas = [
            riskArea('frontend', 'Frontend/UI', haystack, ['frontend', 'flutter', 'dart', 'widget', 'ui', '页面', '指挥台', 'command_center', '.dart']),
            riskArea('backend', 'Backend/API', haystack, ['backend', 'spring', 'groovy', 'maven', 'controller', 'service', 'api', '后端', '.groovy', 'mvn']),
            riskArea('message-bus', 'Message Bus', haystack, ['rabbitmq', 'stomp', 'websocket', 'message bus', '消息队列', 'deviceprotocol', 'command', 'event', 'payload']),
            riskArea('memory', 'Memory/Knowledge', haystack, ['knowledge', 'memory', '学习', '知识库', 'projectlearning', 'context', 'bridge']),
            riskArea('testing', 'Testing/Quality', haystack, ['test', '测试', 'tester', 'quality', '验证', '回归'])
        ].findAll { (it.score as int) > 0 }
        if (areas.isEmpty()) {
            areas << [id: 'general', label: 'General Regression', score: 1, reasons: ['No specific risk signal matched; run a small regression route.']]
        }
        List<String> covers = areas.collect { it.id.toString() }.unique()
        return [
            level          : riskLevel(areas),
            areas          : areas.sort { a, b -> (b.score as int) <=> (a.score as int) },
            impactedCovers : covers,
            changedFiles   : normalizedList(request?.changedFiles ?: request?.files ?: request?.paths),
            routeHint      : covers.contains('message-bus') || covers.size() >= 3 ? 'release-confidence' : 'fast-feedback'
        ] as Map<String, Object>
    }

    private List<Map<String, Object>> buildRoutes(List<ResolvedTesterCommand> commands, Map<String, Object> riskProfile) {
        if (!commands) {
            return []
        }
        List<String> impacted = normalizedList(riskProfile?.impactedCovers)
        List<Map> scored = commands.collect { command -> [command: command, score: capabilityScore(command, impacted)] }
            .sort { a, b -> (b.score as int) <=> (a.score as int) ?: costWeight((a.command as ResolvedTesterCommand).cost) <=> costWeight((b.command as ResolvedTesterCommand).cost) }
        List<ResolvedTesterCommand> targeted = scored.findAll { (it.score as int) > 0 }.collect { it.command as ResolvedTesterCommand }
        List<ResolvedTesterCommand> fast = (targeted ?: commands)
            .findAll { it.cost != 'high' }
            .take(Math.max(1, Math.min(3, properties.tester.maxRepairAttempts + 2)))
        if (fast.isEmpty()) {
            fast = (targeted ?: commands).take(1)
        }
        List<ResolvedTesterCommand> release = commands.sort { a, b -> costWeight(a.cost) <=> costWeight(b.cost) ?: b.confidence <=> a.confidence }
        List<Map<String, Object>> routes = []
        routes << routeCard('fast-feedback', 'Fast Feedback', fast, 'Lowest useful cost for quick repair feedback.', riskProfile)
        if (targeted && commandIdsFor(targeted) != commandIdsFor(fast)) {
            routes << routeCard('targeted-risk', 'Targeted Risk', targeted.take(5), 'Capabilities matched to the current risk profile.', riskProfile)
        }
        if (commandIdsFor(release) != commandIdsFor(fast)) {
            routes << routeCard('release-confidence', 'Release Confidence', release, 'Broader confidence route before considering the task done.', riskProfile)
        }
        String hinted = text(riskProfile?.routeHint)
        routes.each { route ->
            route.recommended = route.id == hinted || (!routes.any { it.id == hinted } && route.id == 'fast-feedback')
        }
        if (!routes.any { it.recommended == true }) {
            routes.first().recommended = true
        }
        return routes
    }

    private Map<String, Object> selectRoute(List<Map<String, Object>> routes, Map request) {
        if (!routes) {
            return [:]
        }
        String requested = text(request?.routeId)
        if (requested && requested != 'recommended') {
            Map found = routes.find { it.id == requested }
            if (found) {
                return found
            }
        }
        return recommendedRoute(routes) ?: routes.first()
    }

    private static Map<String, Object> recommendedRoute(List<Map<String, Object>> routes) {
        return routes?.find { it.recommended == true } ?: (routes ? routes.first() : [:])
    }

    private static Map<String, Object> routeCard(String id,
                                                 String name,
                                                 List<ResolvedTesterCommand> commands,
                                                 String reason,
                                                 Map riskProfile) {
        List<String> commandIds = commandIdsFor(commands)
        int score = commands.collect { capabilityScore(it, normalizedList(riskProfile?.impactedCovers)) }.sum(0) as int
        return [
            id          : id,
            name        : name,
            commandIds  : commandIds,
            cost        : routeCost(commands),
            confidence  : routeConfidence(commands),
            score       : score,
            reason      : reason,
            riskCoverage: normalizedList(riskProfile?.impactedCovers).findAll { cover -> commands.any { it.covers.contains(cover) || it.tags.contains(cover) } },
            recommended : false
        ] as Map<String, Object>
    }

    private static int capabilityScore(ResolvedTesterCommand command, List<String> impacted) {
        List<String> searchable = (command.covers + command.tags + [command.type, command.id, command.name, command.reason]).collect { lower(it) }
        int score = 0
        impacted.each { cover ->
            String normalized = lower(cover)
            if (searchable.any { it.contains(normalized) || normalized.contains(it) }) {
                score += 5
            }
        }
        if (command.confidence == 'high') score += 2
        if (command.cost == 'low') score += 1
        return score
    }

    private static Map<String, Object> riskArea(String id, String label, String haystack, List<String> needles) {
        List<String> reasons = needles.findAll { needle -> haystack.contains(lower(needle)) }.unique()
        return [
            id     : id,
            label  : label,
            score  : reasons.size(),
            reasons: reasons.take(6)
        ] as Map<String, Object>
    }

    private static String riskLevel(List<Map<String, Object>> areas) {
        int score = areas.collect { it.score as int }.sum(0) as int
        if (score >= 8 || areas.size() >= 4) return 'high'
        if (score >= 4 || areas.size() >= 2) return 'medium'
        return 'low'
    }

    private static Map<String, Object> evidencePacket(TesterCommandResult result) {
        boolean passed = result.status == DeviceProtocol.STATUS_COMPLETED
        List<String> signalLines = interestingLines(result.output ?: result.message)
        List<String> files = extractFileHints(result.output)
        String symptom = passed
            ? "Capability '${result.name}' passed."
            : (signalLines ? signalLines.first() : result.message ?: "Capability '${result.name}' failed.")
        String likelyCause = passed ? 'No failure evidence.' : inferLikelyCause(result, signalLines)
        List<String> scopes = (files.collect { scopeFromFile(it) } + result.covers + result.tags).findAll { it }.unique().take(8)
        String repairPrompt = passed ? '' : buildRepairPrompt(result, symptom, likelyCause, files, scopes)
        return [
            kind             : 'quality-evidence-packet',
            capabilityId     : result.id,
            capabilityName   : result.name,
            status           : result.status,
            parser           : result.evidenceParser,
            symptom          : symptom,
            likelyCause      : likelyCause,
            failedFiles      : files.take(12),
            suggestedFixScope: scopes,
            importantLines   : signalLines.take(12),
            repairPrompt     : repairPrompt,
            severity         : passed ? 'none' : result.timedOut ? 'medium' : 'high'
        ].findAll { it.value != null && it.value != '' && (!(it.value instanceof Collection) || !it.value.isEmpty()) } as Map<String, Object>
    }

    private static Map<String, Object> buildEvidenceSummary(List<TesterCommandResult> results,
                                                            String task,
                                                            Map<String, Object> route) {
        List<TesterCommandResult> failed = results.findAll { it.status != DeviceProtocol.STATUS_COMPLETED }
        List<Map<String, Object>> packets = failed.collect { it.evidencePacket ?: evidencePacket(it) }
        String repairPrompt = packets
            ? """Codex repair request from EvoForge tester:
Task: ${compact(task, 1200)}
Route: ${text(route?.id)}
Failures:
${packets.withIndex().collect { entry -> "${(entry[1] as int) + 1}. ${(entry[0] as Map).capabilityName}: ${(entry[0] as Map).symptom}\nLikely cause: ${(entry[0] as Map).likelyCause}\nSuggested scope: ${(((entry[0] as Map).suggestedFixScope ?: []) as List).join(', ')}" }.join('\n')}

Use the evidence above to make the smallest corrective change, then ask EvoForge tester to rerun the affected capability ids: ${failed.collect { it.id }.unique().join(', ')}.""".stripIndent().trim()
            : ''
        return [
            passed          : failed.isEmpty(),
            failedCount     : failed.size(),
            failedCapabilityIds: failed.collect { it.id }.unique(),
            evidencePackets : packets,
            repairPrompt    : repairPrompt,
            nextAction      : failed.isEmpty() ? 'record-success' : 'repair-and-rerun-affected-capabilities'
        ].findAll { it.value != null && it.value != '' && (!(it.value instanceof Collection) || !it.value.isEmpty()) } as Map<String, Object>
    }

    private static List<String> interestingLines(Object output) {
        return (output ?: '').toString().readLines()
            .collect { it.trim() }
            .findAll { line ->
                String lower = lower(line)
                line && (
                    lower.contains('error') ||
                    lower.contains('failure') ||
                    lower.contains('failed') ||
                    lower.contains('exception') ||
                    lower.contains('expected') ||
                    lower.contains('missing') ||
                    lower.contains('no signature') ||
                    lower.contains('undefined') ||
                    lower.contains('cannot find') ||
                    lower.contains('失败') ||
                    lower.contains('错误')
                )
            }
            .unique()
            .take(20)
    }

    private static List<String> extractFileHints(Object output) {
        String text = (output ?: '').toString()
        List<String> files = []
        (text =~ /([A-Za-z0-9_@.\/-]+\.(groovy|java|dart|kt|ts|tsx|js|jsx|yaml|yml|sql))(?::\d+)?/).each { match ->
            files << match[1].toString()
        }
        return files.findAll { !it.startsWith('http') }.unique().take(20)
    }

    private static String inferLikelyCause(TesterCommandResult result, List<String> lines) {
        String text = lower(([result.message, result.output] + lines).join('\n'))
        if (result.timedOut) return 'The capability timed out; the command may be hanging, waiting for external services, or exceeding the configured budget.'
        if (text.contains('missingmethodexception') || text.contains('no signature of method')) return 'Groovy API or helper usage does not match the runtime version.'
        if (text.contains('no named parameter') || text.contains('required named parameter') || text.contains('is required')) return 'A Flutter/Dart model or widget constructor changed without updating all call sites or test fixtures.'
        if (text.contains('checksum mismatch') || text.contains('flyway')) return 'Database migration history and local migration files are inconsistent.'
        if (text.contains('connection refused') || text.contains('connection reset') || text.contains('websocket')) return 'The test depends on an unavailable or mismatched external service connection.'
        if (text.contains('cannot find symbol') || text.contains('unable to resolve class')) return 'A type, import, or generated API contract is missing after the change.'
        if (text.contains('expected') && text.contains('actual')) return 'Behavior changed relative to an existing assertion; update implementation or adjust the assertion only if the new behavior is intentional.'
        return 'The failing capability produced error evidence; inspect the important lines and repair the smallest affected scope.'
    }

    private static String buildRepairPrompt(TesterCommandResult result,
                                            String symptom,
                                            String likelyCause,
                                            List<String> files,
                                            List<String> scopes) {
        return """EvoForge tester found a failure.
Capability: ${result.id} / ${result.name}
Command: ${result.command}
Symptom: ${symptom}
Likely cause: ${likelyCause}
Files: ${files.join(', ')}
Suggested scope: ${scopes.join(', ')}

Please fix only the affected scope, preserve unrelated user changes, and rerun capability '${result.id}' afterward.""".stripIndent().trim()
    }

    private void recordKnowledge(Map request, TesterRunResult result) {
        if (!properties.tester.recordKnowledge || !knowledgeService || !result?.projectKey) {
            return
        }
        Instant now = result.completedAt ?: Instant.now()
        String scope = "project:${result.projectKey}".toString()
        String taskId = text(request?.taskId) ?: UUID.randomUUID().toString()
        Map<String, Object> value = [
            kind       : 'tester-run',
            taskId     : taskId,
            projectKey : result.projectKey,
            trigger    : result.trigger,
            userTask   : result.task,
            status     : result.status,
            passed     : result.passed,
            message    : result.message,
            routeId    : result.routeId,
            route      : result.route,
            riskProfile: result.riskProfile,
            evidenceSummary: result.evidenceSummary,
            repairPrompt: compact(result.repairPrompt, 3000),
            commands   : result.commands.collect { it.toMap(false) },
            codexStatus: text(request?.codexStatus),
            codexOutput: compact(request?.codexOutput ?: request?.codexMessage, 3000),
            recordedAt : now.toString()
        ].findAll { it.value != null && it.value != '' } as Map<String, Object>
        List<String> tags = [
            'project',
            result.projectKey,
            'tester',
            'test-result',
            result.passed ? 'success' : 'error'
        ]
        AgentKnowledgeFact runFact = knowledgeService.upsert(
            "project.${result.projectKey}.tester.run.${safeInstant(now)}.${shortId(taskId)}".toString(),
            objectMapper.writeValueAsString(value),
            scope,
            tags,
            'evoforge-tester',
            result.passed ? 0.86d : 0.78d
        )
        knowledgeService.upsert(
            "project.${result.projectKey}.tester.latest".toString(),
            objectMapper.writeValueAsString(value + [knowledgeKey: runFact.key]),
            scope,
            tags + ['latest-test'],
            'evoforge-tester',
            result.passed ? 0.86d : 0.78d
        )
    }

    private TesterRunResult failedRun(Map request, TesterWorkspace workspace, String message, Instant startedAt) {
        TesterRunResult result = new TesterRunResult(
            status: DeviceProtocol.STATUS_FAILED,
            message: message,
            output: message,
            recoverable: true,
            projectKey: workspace?.key ?: projectKey(request),
            workspace: workspace?.path?.toString(),
            task: compact(request?.task ?: request?.query ?: request?.input, 2000),
            trigger: text(request?.trigger) ?: 'tester',
            startedAt: startedAt,
            completedAt: Instant.now(),
            commands: []
        )
        recordKnowledge(request, result)
        return result
    }

    private TesterWorkspace resolveWorkspace(String requestedKey) {
        Map<String, String> workspaces = properties.codexTask.workspaces ?: [:]
        String key = text(requestedKey) ?: text(properties.codexTask.defaultWorkspace)
        String configuredPath = key ? workspaces[key] : null
        if (key && !configuredPath) {
            return TesterWorkspace.invalid(key, "Tester workspace '${key}' is not configured".toString())
        }

        Path path = Path.of(configuredPath ?: properties.codexTask.workingDirectory ?: '.').toAbsolutePath().normalize()
        if (!Files.isDirectory(path)) {
            return TesterWorkspace.invalid(key ?: 'legacy-working-directory', "Tester workspace directory does not exist: ${path}".toString())
        }
        return TesterWorkspace.valid(key ?: 'legacy-working-directory', path)
    }

    private static Map contextRequest(Map request) {
        return [
            contextPolicy: request?.contextPolicy instanceof Map ? request.contextPolicy : [
                maxFacts       : 4,
                maxChars       : 2400,
                maxCharsPerFact: 600
            ]
        ]
    }

    private static Map requestFromCommand(DeviceCommandMessage command, CodexTaskResult codexResult) {
        Map tester = testerConfig(command)
        return [
            taskId       : command?.taskId,
            projectKey   : projectKey(command),
            task         : command?.text,
            trigger      : command?.type,
            commandIds   : tester.commandIds ?: tester.commands,
            routeId      : tester.routeId,
            changedFiles  : tester.changedFiles ?: tester.files,
            contextPolicy: tester.contextPolicy,
            codexStatus  : codexResult?.status,
            codexOutput  : codexResult?.output,
            codexMessage : codexResult?.message
        ].findAll { it.value != null } as Map
    }

    static Map testerConfig(DeviceCommandMessage command) {
        Object raw = command?.attributes?.evoforgeTester ?: command?.attributes?.evoforgeTesting ?: command?.attributes?.tester
        if (raw instanceof Map) {
            Map config = new LinkedHashMap(raw as Map)
            config.enabled = config.enabled == true
            return config
        }
        return [enabled: raw == true || command?.attributes?.testWithEvoForge == true]
    }

    private static String projectKey(Map request) {
        return text(request?.projectKey ?: request?.workspaceKey ?: request?.project)
    }

    private static String projectKey(DeviceCommandMessage command) {
        return text(command?.attributes?.projectKey ?: command?.attributes?.workspaceKey ?: command?.attributes?.project)
    }

    private static List<String> commandIds(Map request) {
        Object raw = request?.commandIds ?: request?.commands ?: request?.commandId
        if (raw instanceof Collection) {
            return raw.collect { text(it) }.findAll { it }.unique()
        }
        return [text(raw)].findAll { it }
    }

    private static Path resolveWorkingDirectory(Path workspace, Object raw) {
        String value = text(raw)
        Path path = value ? Path.of(value) : Path.of('.')
        return path.isAbsolute() ? path.normalize() : workspace.resolve(path).normalize()
    }

    private static boolean isInside(Path root, Path child) {
        return child.toAbsolutePath().normalize().startsWith(root.toAbsolutePath().normalize())
    }

    private static List<String> tokenize(String value) {
        String input = text(value)
        List<String> tokens = []
        StringBuilder current = new StringBuilder()
        Character quote = null
        boolean escaping = false
        input.each { String ch ->
            if (escaping) {
                current.append(ch)
                escaping = false
                return
            }
            if (ch == '\\') {
                escaping = true
                return
            }
            if (quote) {
                if (ch == quote.toString()) {
                    quote = null
                } else {
                    current.append(ch)
                }
                return
            }
            if (ch == '"' || ch == "'") {
                quote = ch.charAt(0)
                return
            }
            if (Character.isWhitespace(ch.charAt(0))) {
                if (current.length() > 0) {
                    tokens << current.toString()
                    current.setLength(0)
                }
                return
            }
            current.append(ch)
        }
        if (escaping) {
            current.append('\\')
        }
        if (current.length() > 0) {
            tokens << current.toString()
        }
        return quote ? [] : tokens
    }

    private static Map<String, Object> commandCard(ResolvedTesterCommand command) {
        return [
            id              : command.id,
            name            : command.name,
            type            : command.type,
            covers          : command.covers,
            tags            : command.tags,
            cost            : command.cost,
            confidence      : command.confidence,
            evidenceParser  : command.evidenceParser,
            command         : command.displayCommand,
            workingDirectory: command.workingDirectory?.toString(),
            timeoutSeconds  : command.timeoutSeconds,
            reason          : command.reason,
            fallbackCommandIds: command.fallbackCommandIds,
            repairScopes    : command.repairScopes
        ].findAll { it.value != null && it.value != '' && (!(it.value instanceof Collection) || !it.value.isEmpty()) } as Map<String, Object>
    }

    private static String outputSummary(List<TesterCommandResult> results) {
        return results.collect { result ->
            String head = "[${result.status}] ${result.name} (${result.command})"
            String body = result.output ? "\n${compact(result.output, 5000)}" : ''
            return "${head}${body}".toString()
        }.join('\n\n')
    }

    private static String taskText(Map request) {
        return text(request?.task ?: request?.query ?: request?.input)
    }

    private static List<String> routeCommandIds(Map route) {
        return normalizedList(route?.commandIds)
    }

    private static List<String> commandIdsFor(List<ResolvedTesterCommand> commands) {
        return (commands ?: []).collect { it.id }.findAll { it }.unique()
    }

    private static String routeCost(List<ResolvedTesterCommand> commands) {
        int max = (commands ?: []).collect { costWeight(it.cost) }.max() ?: 1
        return max >= 3 ? 'high' : max == 2 ? 'medium' : 'low'
    }

    private static String routeConfidence(List<ResolvedTesterCommand> commands) {
        List<String> values = (commands ?: []).collect { it.confidence ?: 'medium' }
        if (values.contains('high')) return 'high'
        if (values.contains('medium')) return 'medium'
        return 'low'
    }

    private static int costWeight(String cost) {
        switch (text(cost)) {
            case 'low':
                return 1
            case 'high':
                return 3
            default:
                return 2
        }
    }

    private static String inferType(String id, String name, String command, Object workingDirectory) {
        String value = lower([id, name, command, workingDirectory].join(' '))
        if (value.contains('build')) return 'build'
        if (value.contains('lint') || value.contains('analyze')) return 'static-analysis'
        if (value.contains('smoke')) return 'smoke'
        if (value.contains('ui') || value.contains('frontend') || value.contains('flutter')) return 'ui-regression'
        if (value.contains('api') || value.contains('backend') || value.contains('mvn') || value.contains('spring')) return 'backend-regression'
        return 'regression'
    }

    private static List<String> inferCovers(String id, String name, String command, Object workingDirectory) {
        String value = lower([id, name, command, workingDirectory].join(' '))
        List<String> covers = []
        if (value.contains('frontend') || value.contains('flutter') || value.contains('.dart')) covers << 'frontend'
        if (value.contains('backend') || value.contains('mvn') || value.contains('spring') || value.contains('groovy')) covers << 'backend'
        if (value.contains('rabbit') || value.contains('stomp') || value.contains('message') || value.contains('device')) covers << 'message-bus'
        if (value.contains('knowledge') || value.contains('memory') || value.contains('bridge')) covers << 'memory'
        if (value.contains('test') || value.contains('quality') || value.contains('tester')) covers << 'testing'
        return covers.unique() ?: ['general']
    }

    private static String inferEvidenceParser(String command) {
        String value = lower(command)
        if (value.contains('flutter') || value.contains('dart')) return 'flutter-test'
        if (value.contains('mvn') || value.contains('gradle')) return 'jvm-test'
        if (value.contains('npm') || value.contains('pnpm') || value.contains('yarn')) return 'node-test'
        if (value.contains('pytest')) return 'pytest'
        return 'generic'
    }

    private static String normalizeChoice(Object raw, String fallback, List<String> allowed) {
        String value = lower(raw)
        return allowed.contains(value) ? value : fallback
    }

    private static List<String> normalizedList(Object value) {
        if (value instanceof Collection) {
            return value.collect { text(it) }.findAll { it }.unique()
        }
        String single = text(value)
        return single ? [single] : []
    }

    private static String scopeFromFile(String file) {
        String value = text(file)
        if (!value) return ''
        if (value.contains('/')) {
            List<String> parts = value.split('/').findAll { it }
            return parts.size() >= 2 ? parts.take(2).join('/') : parts.first()
        }
        return value
    }

    private static String relativePath(Path root, Path child) {
        try {
            return root.toAbsolutePath().normalize().relativize(child.toAbsolutePath().normalize()).toString() ?: '.'
        } catch (Exception ignored) {
            return child?.toString()
        }
    }

    private static long durationMs(Instant startedAt) {
        return Duration.between(startedAt, Instant.now()).toMillis()
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

    private static String lower(Object value) {
        return text(value).toLowerCase(Locale.ROOT)
    }

    private static String shortId(String value) {
        String text = value ?: UUID.randomUUID().toString()
        return text.length() <= 8 ? text : text.substring(text.length() - 8)
    }

    private static String safeInstant(Instant instant) {
        return instant.toString().replaceAll('[^0-9A-Za-z]+', '')
    }

    private static String normalizeBaseUrl(String baseUrl) {
        String value = text(baseUrl) ?: 'http://localhost:18080'
        return value.endsWith('/') ? value.substring(0, value.length() - 1) : value
    }
}

class TesterWorkspace {
    boolean valid
    String key
    Path path
    String message

    static TesterWorkspace valid(String key, Path path) {
        return new TesterWorkspace(valid: true, key: key, path: path)
    }

    static TesterWorkspace invalid(String key, String message) {
        return new TesterWorkspace(valid: false, key: key, message: message)
    }
}

class ResolvedTesterCommand {
    boolean valid
    String id
    String name
    String type
    List<String> covers = []
    List<String> tags = []
    String cost
    String confidence
    String evidenceParser
    List<String> fallbackCommandIds = []
    List<String> repairScopes = []
    List<String> tokens = []
    String displayCommand
    Path workingDirectory
    int timeoutSeconds
    String reason
    String message

    static ResolvedTesterCommand invalid(String message) {
        return new ResolvedTesterCommand(valid: false, message: message)
    }
}

class TesterCommandResult {
    String id
    String name
    String type
    List<String> covers = []
    List<String> tags = []
    String cost
    String confidence
    String evidenceParser
    String command
    String workingDirectory
    String status
    Integer exitCode
    boolean timedOut
    long durationMs
    String output
    String message
    Map<String, Object> evidencePacket = [:]

    Map<String, Object> toMap(boolean includeOutput = true) {
        return [
            id              : id,
            name            : name,
            type            : type,
            covers          : covers,
            tags            : tags,
            cost            : cost,
            confidence      : confidence,
            evidenceParser  : evidenceParser,
            command         : command,
            workingDirectory: workingDirectory,
            status          : status,
            exitCode        : exitCode,
            timedOut        : timedOut,
            durationMs      : durationMs,
            message         : message,
            evidencePacket  : evidencePacket,
            output          : includeOutput ? output : null
        ].findAll { it.value != null && it.value != '' && (!(it.value instanceof Collection) || !it.value.isEmpty()) && (!(it.value instanceof Map) || !it.value.isEmpty()) } as Map<String, Object>
    }
}

class TesterRunResult {
    String status
    String message
    String output
    boolean recoverable
    String projectKey
    String workspace
    String task
    String trigger
    Instant startedAt
    Instant completedAt
    String routeId
    Map<String, Object> route = [:]
    Map<String, Object> riskProfile = [:]
    Map<String, Object> evidenceSummary = [:]
    String repairPrompt
    List<TesterCommandResult> commands = []

    boolean isPassed() {
        return status == DeviceProtocol.STATUS_COMPLETED
    }

    Map<String, Object> toMap(boolean includeOutput = true) {
        return [
            status     : status,
            passed     : passed,
            message    : message,
            output     : includeOutput ? output : null,
            recoverable: recoverable,
            projectKey : projectKey,
            workspace  : workspace,
            task       : task,
            trigger    : trigger,
            startedAt  : startedAt?.toString(),
            completedAt: completedAt?.toString(),
            routeId    : routeId,
            route      : route,
            riskProfile: riskProfile,
            evidenceSummary: evidenceSummary,
            repairPrompt: repairPrompt,
            commands   : commands.collect { it.toMap(includeOutput) }
        ].findAll { it.value != null && it.value != '' && (!(it.value instanceof Collection) || !it.value.isEmpty()) && (!(it.value instanceof Map) || !it.value.isEmpty()) } as Map<String, Object>
    }
}
