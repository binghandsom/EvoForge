package com.evoforge.agent

import com.evoforge.api.SkillCreateRequest
import com.evoforge.api.SkillProposalRequest
import com.evoforge.codex.CodexQuestionBridgeService
import com.evoforge.core.EvoForgeProperties
import com.evoforge.device.DeviceEventPublisher
import com.evoforge.device.DeviceProtocol
import com.evoforge.device.DeviceTaskEvent
import com.evoforge.llm.ModelHub
import com.evoforge.model.SkillResult
import com.evoforge.skills.SkillService
import com.evoforge.workbench.SkillWorkbenchService
import com.fasterxml.jackson.databind.ObjectMapper
import org.springframework.stereotype.Service

@Service
class AgentRuntimeService {
    private final ModelHub modelHub
    private final AgentToolRegistry toolRegistry
    private final AgentKnowledgeService knowledgeService
    private final AgentConversationMemoryService conversationMemoryService
    private final DeviceEventPublisher eventPublisher
    private final EvoForgeProperties properties
    private final ObjectMapper objectMapper
    private final CodexQuestionBridgeService questionBridgeService
    private final SkillWorkbenchService workbenchService
    private final SkillService skillService

    AgentRuntimeService(ModelHub modelHub,
                        AgentToolRegistry toolRegistry,
                        AgentKnowledgeService knowledgeService,
                        AgentConversationMemoryService conversationMemoryService,
                        DeviceEventPublisher eventPublisher,
                        EvoForgeProperties properties,
                        ObjectMapper objectMapper,
                        CodexQuestionBridgeService questionBridgeService,
                        SkillWorkbenchService workbenchService,
                        SkillService skillService) {
        this.modelHub = modelHub
        this.toolRegistry = toolRegistry
        this.knowledgeService = knowledgeService
        this.conversationMemoryService = conversationMemoryService
        this.eventPublisher = eventPublisher
        this.properties = properties
        this.objectMapper = objectMapper
        this.questionBridgeService = questionBridgeService
        this.workbenchService = workbenchService
        this.skillService = skillService
    }

    AgentRunResult run(String input, Map<String, Object> attributes = [:]) {
        Map<String, Object> runAttributes = new LinkedHashMap<>(attributes ?: [:])
        String threadId = conversationMemoryService.resolveThreadId(runAttributes)
        runAttributes.threadId = threadId
        List<AgentConversationTurn> conversationContext = conversationMemoryService.recent(threadId, properties.agent.maxThreadTurns)
        if ((input ?: '').trim()) {
            conversationMemoryService.append(threadId, 'user', input ?: '', [
                taskId: runAttributes.taskId,
                userId: runAttributes.userId,
                source: 'agent-runtime'
            ].findAll { it.value != null } as Map<String, Object>)
        }

        List<Map<String, Object>> observations = []
        List<Map<String, Object>> routes = []
        List<Map<String, Object>> writes = []
        AgentToolContext context = new AgentToolContext(
            taskId: runAttributes?.taskId?.toString(),
            userId: runAttributes?.userId?.toString(),
            deviceId: runAttributes?.deviceId?.toString(),
            attributes: runAttributes
        )

        int maxSteps = Math.max(1, Math.min(properties.agent.maxSteps, 20))
        for (int step = 1; step <= maxSteps; step++) {
            AgentPlannerTurn turn = plan(input, observations, routes, conversationContext, runAttributes)
            routes = mergeRoutes(routes, turn.routes)
            persistKnowledge(turn.knowledgeWrites, writes)
            publishProgress(runAttributes, step, 'plan', turn.thought ?: 'Planner selected next action.', [
                thought        : turn.thought,
                selectedRouteId: turn.selectedRouteId,
                routes         : turn.routes ?: [],
                action         : turn.action ?: [:],
                skillProposal  : turn.skillProposal ?: [:]
            ])

            if (turn.finalAnswer) {
                if (shouldOfferSkillProposal(input, routes, observations) &&
                    (looksBlockedFinalAnswer(turn.finalAnswer) || explicitlyRequestsSkill(input))) {
                    AgentRunResult skillResult = handleSkillProposal(input, turn.skillProposal, observations, routes, writes, runAttributes)
                    if (skillResult) {
                        return finishRun(threadId, conversationContext, skillResult)
                    }
                }
                return finishRun(threadId, conversationContext, new AgentRunResult(
                    success: true,
                    output: turn.finalAnswer,
                    routes: routes,
                    observations: observations,
                    knowledgeWrites: writes,
                    stopReason: 'final'
                ))
            }

            if (turn.skillProposal) {
                AgentRunResult skillResult = handleSkillProposal(input, turn.skillProposal, observations, routes, writes, runAttributes)
                if (skillResult) {
                    return finishRun(threadId, conversationContext, skillResult)
                }
            }

            Map<String, Object> action = turn.action ?: fallbackAction(step, input)
            if (!action?.tool) {
                if (shouldOfferSkillProposal(input, routes, observations)) {
                    AgentRunResult skillResult = handleSkillProposal(input, null, observations, routes, writes, runAttributes)
                    if (skillResult) {
                        return finishRun(threadId, conversationContext, skillResult)
                    }
                }
                return finishRun(threadId, conversationContext, new AgentRunResult(
                    success: false,
                    output: '没有可执行的下一步动作。',
                    routes: routes,
                    observations: observations,
                    knowledgeWrites: writes,
                    stopReason: 'no-action'
                ))
            }

            AgentToolResult result = toolRegistry.execute(action.tool.toString(), (action.args ?: [:]) as Map<String, Object>, context)
            Map<String, Object> observation = [
                step           : step,
                thought        : turn.thought,
                routeId        : turn.selectedRouteId,
                tool           : action.tool,
                args           : action.args ?: [:],
                success        : result.success,
                output         : result.output,
                error          : result.error,
                meta           : result.meta ?: [:],
                availableRoutes: routes
            ]
            observations << observation
            publishProgress(runAttributes, step, 'observe', result.success ? "Tool completed: ${action.tool}".toString() : "Tool failed: ${action.tool}".toString(), [
                observation: observation
            ])
            learnFromObservation(observation, writes)
        }

        if (shouldOfferSkillProposal(input, routes, observations)) {
            AgentRunResult skillResult = handleSkillProposal(input, null, observations, routes, writes, runAttributes)
            if (skillResult) {
                return finishRun(threadId, conversationContext, skillResult)
            }
        }

        String answer = summarize(input, routes, observations, conversationContext, runAttributes)
        return finishRun(threadId, conversationContext, new AgentRunResult(
            success: true,
            output: answer,
            routes: routes,
            observations: observations,
            knowledgeWrites: writes,
            stopReason: 'max-steps'
        ))
    }

    private AgentPlannerTurn plan(String input,
                                  List<Map<String, Object>> observations,
                                  List<Map<String, Object>> routes,
                                  List<AgentConversationTurn> conversationContext,
                                  Map<String, Object> attributes) {
        String prompt = buildPlannerPrompt(input, observations, routes, conversationContext)
        String response = modelHub.getLlm(attributes.llm?.toString()).chat(prompt, [temperature: 0.2d, maxTokens: 2400] as Map<String, Object>)
        Map parsed = tryParseJson(response)
        if (!parsed) {
            return new AgentPlannerTurn(
                thought: 'Planner response was not JSON; using bootstrap fallback.',
                routes: routes ?: [[id: 'bootstrap', status: 'active', rationale: 'Collect local context before planning further.']],
                action: fallbackAction(observations.size() + 1, input)
            )
        }
        return new AgentPlannerTurn(
            thought: parsed.thought?.toString(),
            routes: parsed.routes instanceof Collection ? parsed.routes.collect { it as Map<String, Object> } : [],
            selectedRouteId: parsed.selectedRouteId?.toString(),
            action: parsed.action instanceof Map ? parsed.action as Map<String, Object> : null,
            skillProposal: parsed.skillProposal instanceof Map ? parsed.skillProposal as Map<String, Object> : null,
            finalAnswer: parsed.finalAnswer?.toString(),
            knowledgeWrites: parsed.knowledgeWrites instanceof Collection ? parsed.knowledgeWrites.collect { it as Map<String, Object> } : []
        )
    }

    private String buildPlannerPrompt(String input,
                                      List<Map<String, Object>> observations,
                                      List<Map<String, Object>> routes,
                                      List<AgentConversationTurn> conversationContext) {
        String knowledgeQuery = conversationMemoryService.searchableText(conversationContext, input ?: '')
        def facts = knowledgeService.search(knowledgeQuery, properties.agent.maxKnowledgeResults).collect { KnowledgeSearchTool.toView(it) }
        return """
You are EvoForge's task planner. You do not execute operations yourself; you choose tools.
The system is intentionally dynamic: produce multiple possible routes, execute one low-risk next action, observe the result, then adapt.
Prefer stable reusable knowledge, but verify cheap facts when they may drift. When a route fails, preserve the failure and try another plausible route.
You have OpenClaw-style memory layers:
- Thread memory is the recent conversation in this same thread. Use it to resolve follow-ups, pronouns, corrections, and "continue that" requests.
- Knowledge facts are durable reusable memories. Only write stable facts there; do not turn every chat message into long-term knowledge.
Latest user message wins if it corrects older thread context.

Return only JSON:
{
  "thought": "short reasoning",
  "routes": [
    {"id":"A","status":"candidate|active|blocked|done","rationale":"why this route may work","next":"short next step"}
  ],
  "selectedRouteId": "A",
  "action": {"tool":"tool.name","args":{}},
  "skillProposal": null,
  "finalAnswer": null,
  "knowledgeWrites": [
    {"key":"stable.fact.key","value":"fact value","scope":"global","tags":["tag"],"confidence":0.8,"source":"planner"}
  ]
}

Rules:
- Return finalAnswer only when the user task is genuinely answered or you need a specific clarification from the user.
- Do not invent filesystem facts. Use system.info, shell.run, path.resolve, and file.list.
- shell.run is not limited by an allowed command list. If a command is useful, run it with argv, or run a shell explicitly such as ["sh","-lc","..."].
- Use image.analyze only after you have concrete readable image paths.
- Use knowledge.upsert or knowledgeWrites for reusable facts only, not temporary outputs.
- Prefer argv arrays for shell.run. Avoid destructive commands.
- If all tool routes are blocked or a reusable capability is clearly missing, return skillProposal with name, description, purpose, workflow, inputs, and expectedOutput instead of finalAnswer. EvoForge will ask the user to confirm or modify the proposal, create the skill, and use it to continue the current task.
- If the user explicitly asks EvoForge to form/create/build a skill, do not leave that as a candidate route only. After cheap verification produces negative evidence, return skillProposal so the user can confirm or modify it.
- Do not ask the user to paste file contents until unrestricted shell/file routes have failed.

User task:
${input ?: ''}

Thread memory (same conversation, chronological, before this user message):
${conversationMemoryService.formatForPrompt(conversationContext)}

Known reusable facts:
${objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(facts)}

Available tools:
${objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(toolRegistry.descriptors())}

Current routes:
${objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(routes ?: [])}

Observations so far:
${objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(observations)}
""".stripIndent()
    }

    private String summarize(String input,
                             List<Map<String, Object>> routes,
                             List<Map<String, Object>> observations,
                             List<AgentConversationTurn> conversationContext,
                             Map<String, Object> attributes) {
        String prompt = """
Summarize the current EvoForge agent run for the user.
Be concrete about what was tried, what worked, what failed, and the next actionable path.
Respect the same-thread conversation context when explaining follow-up tasks.

Task: ${input ?: ''}
Thread memory:
${conversationMemoryService.formatForPrompt(conversationContext)}
Routes:
${objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(routes)}
Observations:
${objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(observations)}
""".stripIndent()
        try {
            return modelHub.getLlm(attributes.llm?.toString()).chat(prompt, [temperature: 0.2d, maxTokens: 1200] as Map<String, Object>)
        } catch (Exception ignored) {
            return "已执行 ${observations.size()} 步，但还没有形成最终答案。最后一步结果：${observations ? observations.last() : '无'}".toString()
        }
    }

    private AgentRunResult handleSkillProposal(String input,
                                               Map<String, Object> rawProposal,
                                               List<Map<String, Object>> observations,
                                               List<Map<String, Object>> routes,
                                               List<Map<String, Object>> writes,
                                               Map<String, Object> attributes) {
        if (!properties.agent.skillFallbackEnabled) {
            return null
        }
        Map<String, Object> proposal = normalizeSkillProposal(input, rawProposal, observations, routes)
        if (!attributes?.taskId) {
            return new AgentRunResult(
                success: false,
                output: skillProposalText(proposal) + '\n\n当前任务没有 taskId，无法通过移动端确认创建 skill。',
                routes: routes,
                observations: observations,
                knowledgeWrites: writes,
                stopReason: 'skill-proposal-needs-confirmation'
            )
        }
        Map<String, Object> answer = questionBridgeService?.ask([
            questionId      : "skill-${UUID.randomUUID()}".toString(),
            taskId          : attributes.taskId,
            userId          : attributes.userId,
            deviceId        : attributes.deviceId,
            threadId        : attributes.threadId,
            requester       : 'agent-skill-proposal',
            question        : skillProposalText(proposal),
            options         : ['确认创建并继续', '按我补充的方案创建', '取消'],
            allowFreeText   : true,
            timeoutSeconds  : properties.agent.skillProposalTimeoutSeconds,
            skillProposal   : proposal
        ])
        if (answer?.answered != true) {
            return new AgentRunResult(
                success: false,
                output: "已提出 skill 方案，但没有收到确认：${answer?.error ?: '未确认'}".toString(),
                routes: routes,
                observations: observations,
                knowledgeWrites: writes,
                stopReason: 'skill-proposal-timeout'
            )
        }
        String decision = answer.answer?.toString()?.trim() ?: ''
        if (isRejected(decision)) {
            return new AgentRunResult(
                success: false,
                output: '用户取消了 skill 创建，当前任务暂停。',
                routes: routes,
                observations: observations,
                knowledgeWrites: writes,
                stopReason: 'skill-proposal-rejected'
            )
        }
        if (!isPlainApproval(decision)) {
            proposal = refineSkillProposal(input, proposal, decision)
        }
        if (!workbenchService || !skillService) {
            return new AgentRunResult(
                success: false,
                output: skillProposalText(proposal) + '\n\n当前运行时没有可用的 skill 创建服务，无法自动实现。',
                routes: routes,
                observations: observations,
                knowledgeWrites: writes,
                stopReason: 'skill-service-unavailable'
            )
        }

        try {
            String prompt = buildSkillImplementationPrompt(input, proposal, observations, routes)
            def generated = workbenchService.propose(new SkillProposalRequest(
                name: proposal.name?.toString(),
                prompt: prompt,
                codeModel: attributes.codeModel?.toString()
            ))
            def created = skillService.create(new SkillCreateRequest(
                name: generated.name,
                language: generated.language,
                entryClass: generated.entryClass,
                code: generated.code,
                enabled: true,
                metadata: [
                    description   : proposal.description,
                    purpose       : proposal.purpose,
                    workflow      : proposal.workflow,
                    inputs        : proposal.inputs,
                    expectedOutput: proposal.expectedOutput,
                    source        : 'agent-skill-fallback'
                ].findAll { it.value != null } as Map<String, Object>
            ))
            SkillResult result = skillService.execute(created.id, input, [
                agentFallback    : true,
                skillProposal    : proposal,
                previousRoutes   : routes,
                previousObservations: observations
            ] as Map<String, Object>, attributes.llm?.toString(), attributes.codeModel?.toString(), false)
            List<Map<String, Object>> nextObservations = new ArrayList<>(observations)
            nextObservations << [
                step   : (observations?.size() ?: 0) + 1,
                tool   : 'skill.create-and-execute',
                args   : [name: created.name, id: created.id],
                success: result?.success,
                output : result?.output,
                error  : result?.error,
                meta   : [skillId: created.id, skillName: created.name, proposal: proposal]
            ] as Map<String, Object>
            String output = result?.success
                ? "已根据确认方案创建并执行 skill：${created.name}\n\n${result?.output ?: ''}".toString()
                : "已创建 skill：${created.name}，但执行失败：${result?.error ?: 'unknown error'}".toString()
            return new AgentRunResult(
                success: result?.success ?: false,
                output: output,
                routes: routes,
                observations: nextObservations,
                knowledgeWrites: writes,
                stopReason: result?.success ? 'skill-created-and-executed' : 'skill-execution-failed'
            )
        } catch (Exception ex) {
            return new AgentRunResult(
                success: false,
                output: "已确认 skill 方案，但创建或执行失败：${ex.message ?: ex.class.simpleName}".toString(),
                routes: routes,
                observations: observations,
                knowledgeWrites: writes,
                stopReason: 'skill-creation-failed'
            )
        }
    }

    private Map<String, Object> normalizeSkillProposal(String input,
                                                       Map<String, Object> rawProposal,
                                                       List<Map<String, Object>> observations,
                                                       List<Map<String, Object>> routes) {
        Map source = rawProposal ?: [:]
        List<String> workflow = listText(source.workflow)
        if (workflow.isEmpty()) {
            workflow = [
                '读取当前任务、线程上下文、已验证路径和工具失败记录',
                '选择适合该任务的稳定处理流程',
                '生成结构化结果并给出可复用的输出格式'
            ]
        }
        List<String> inputs = listText(source.inputs)
        if (inputs.isEmpty()) {
            inputs = ['用户任务文本', '线程上下文', '当前工具观察记录']
        }
        return [
            name          : text(source.name) ?: inferSkillName(input),
            description   : text(source.description) ?: "为当前任务创建可复用处理能力：${compact(input, 120)}".toString(),
            purpose       : text(source.purpose) ?: '当通用工具路线无法稳定完成同类任务时，用固定流程继续执行。',
            workflow      : workflow,
            inputs        : inputs,
            expectedOutput: text(source.expectedOutput) ?: '可直接交付给用户的结构化结果',
            task          : compact(input, 1000),
            failureSummary: summarizeFailures(observations, routes)
        ].findAll { it.value != null && it.value != '' } as Map<String, Object>
    }

    private Map<String, Object> refineSkillProposal(String input,
                                                    Map<String, Object> proposal,
                                                    String userModification) {
        Map<String, Object> refined = new LinkedHashMap<>(proposal ?: [:])
        refined.userModification = userModification
        refined.description = "${text(refined.description)}\n用户补充/修改：${userModification}".toString().trim()
        refined.purpose = text(refined.purpose) ?: "按用户补充方案处理任务：${compact(input, 120)}".toString()
        return refined
    }

    private String buildSkillImplementationPrompt(String input,
                                                  Map<String, Object> proposal,
                                                  List<Map<String, Object>> observations,
                                                  List<Map<String, Object>> routes) {
        return """
Create a production EvoForge Groovy skill for this confirmed proposal.

The skill must implement com.evoforge.skills.Skill and return com.evoforge.model.SkillResult.
It should be reusable, explain errors clearly, and use context.input plus context.attributes.
If the task requires local files, the skill may use ordinary JVM/file APIs and should not assume one hard-coded path unless the input/attributes contain that path.

Current user task:
${input ?: ''}

Confirmed skill proposal:
${objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(proposal ?: [:])}

Routes already tried:
${objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(routes ?: [])}

Tool observations:
${objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(observations ?: [])}
""".stripIndent()
    }

    private String skillProposalText(Map<String, Object> proposal) {
        List workflow = proposal.workflow instanceof Collection ? proposal.workflow as List : []
        List inputs = proposal.inputs instanceof Collection ? proposal.inputs as List : []
        return """
EvoForge 判断当前通用工具路线已经不足以稳定完成任务，建议先创建一个可复用 skill，创建后会立刻用它继续当前任务。

Skill 名称：${proposal.name}
描述：${proposal.description}
用途：${proposal.purpose}
输入：${inputs.collect { it.toString() }.join('；')}
输出：${proposal.expectedOutput}
工作流：
${workflow.withIndex().collect { item, index -> "${index + 1}. ${item}".toString() }.join('\n')}

你可以回复“确认创建并继续”，也可以直接回复修改后的 skill 方案描述；回复“取消”则暂停当前任务。
""".stripIndent().trim()
    }

    private boolean shouldOfferSkillProposal(String input,
                                             List<Map<String, Object>> routes,
                                             List<Map<String, Object>> observations) {
        if (!properties.agent.skillFallbackEnabled) {
            return false
        }
        boolean anyFailed = (observations ?: []).any { it.success == false }
        boolean anyBlockedObservation = (observations ?: []).any { observationLooksBlocked(it) }
        boolean allBlocked = routes && routes.every {
            ['blocked', 'failed', 'dead', 'stuck'].contains(text(it.status).toLowerCase(Locale.ROOT))
        }
        boolean noSuccessfulObservation = observations && !(observations ?: []).any { it.success == true }
        boolean explicitSkillRequest = explicitlyRequestsSkill(input) || routesHaveSkillProposalPath(routes)
        return anyFailed || allBlocked || noSuccessfulObservation || (explicitSkillRequest && anyBlockedObservation)
    }

    private static boolean explicitlyRequestsSkill(String input) {
        String value = text(input).toLowerCase(Locale.ROOT)
        if (!value) {
            return false
        }
        return value.contains('skill') ||
            ['形成能力', '形成一个能力', '形成可复用能力', '形成工具', '创建能力', '创建工具', '创建技能', '生成技能', '做成能力', '做成工具']
                .any { value.contains(it) }
    }

    private static boolean routesHaveSkillProposalPath(List<Map<String, Object>> routes) {
        return (routes ?: []).any { route ->
            String combined = [
                route.id,
                route.status,
                route.rationale,
                route.next
            ].collect { text(it) }.join(' ').toLowerCase(Locale.ROOT)
            combined.contains('skill') || combined.contains('技能') || combined.contains('可复用能力')
        }
    }

    private static boolean observationLooksBlocked(Map<String, Object> observation) {
        if (!observation) {
            return false
        }
        String tool = text(observation.tool)
        Object output = observation.output
        if (tool == 'path.resolve' && output instanceof Map) {
            return output.exists == false
        }
        if (tool == 'file.list' && output instanceof Map) {
            return output.count instanceof Number && output.count.intValue() == 0
        }
        if (tool == 'shell.run' && output instanceof Map) {
            String stdout = text(output.output)
            int exitCode = output.exitCode instanceof Number ? output.exitCode.intValue() : 0
            return exitCode == 0 && stdout == '' && shellCommandLooksLikeSearch(observation)
        }
        return false
    }

    private static boolean shellCommandLooksLikeSearch(Map<String, Object> observation) {
        Object rawArgv = observation.args instanceof Map ? (observation.args as Map).argv : null
        String command = rawArgv instanceof Collection
            ? rawArgv.collect { text(it) }.join(' ')
            : text(rawArgv)
        String value = command.toLowerCase(Locale.ROOT)
        return ['find ', 'mdfind', 'locate ', 'grep ', 'rg ', 'fd ', '-name ', 'whereis '].any { value.contains(it) }
    }

    private static boolean looksBlockedFinalAnswer(String answer) {
        String value = text(answer)
        if (!value) {
            return false
        }
        return ['无法', '不能', '没法', '失败', '粘贴', '贴出', '提供正文', '需要用户'].any { value.contains(it) }
    }

    private static boolean isRejected(String answer) {
        String value = text(answer).toLowerCase(Locale.ROOT)
        return value in ['取消', '不用', '不要', 'reject', 'no', 'stop'] || value.contains('取消')
    }

    private static boolean isPlainApproval(String answer) {
        String value = text(answer).toLowerCase(Locale.ROOT)
        return value in ['确认', '确认创建并继续', '同意', '可以', '创建', '继续', 'ok', 'yes', 'approve']
    }

    private static String inferSkillName(String input) {
        String value = text(input)
        if (value.contains('视频提示词') || value.contains('剧本')) {
            return 'ScriptToVideoPromptSkill'
        }
        if (value.contains('图片')) {
            return 'LocalImageTaskSkill'
        }
        return 'AgentFallbackSkill'
    }

    private static String summarizeFailures(List<Map<String, Object>> observations,
                                            List<Map<String, Object>> routes) {
        List failed = (observations ?: []).findAll { it.success == false }.collect {
            [
                tool : it.tool,
                error: it.error,
                meta : it.meta
            ].findAll { entry -> entry.value != null }
        }
        return [
            failedObservations: failed,
            routes            : routes ?: []
        ].toString()
    }

    private static List<String> listText(Object value) {
        if (value instanceof Collection) {
            return value.collect { text(it) }.findAll { it }
        }
        String single = text(value)
        return single ? [single] : []
    }

    private static String compact(Object value, int limit) {
        String normalized = text(value).replaceAll('\\s+', ' ')
        return normalized.length() > limit ? normalized.take(limit) + '...' : normalized
    }

    private static String text(Object value) {
        return value == null ? '' : value.toString().trim()
    }

    private AgentRunResult finishRun(String threadId,
                                     List<AgentConversationTurn> conversationContext,
                                     AgentRunResult result) {
        result.threadId = threadId
        result.conversationContext = conversationMemoryService.toView(conversationContext)
        if ((result.output ?: '').trim()) {
            conversationMemoryService.append(threadId, 'assistant', result.output ?: '', [
                source          : 'agent-runtime',
                stopReason      : result.stopReason,
                success         : result.success,
                routeCount      : result.routes?.size() ?: 0,
                observationCount: result.observations?.size() ?: 0
            ] as Map<String, Object>)
        }
        return result
    }

    private void persistKnowledge(List<Map<String, Object>> items, List<Map<String, Object>> writes) {
        (items ?: []).each { item ->
            if (item?.key && item?.value) {
                AgentKnowledgeFact fact = knowledgeService.upsert(
                    item.key?.toString(),
                    item.value?.toString(),
                    item.scope?.toString() ?: 'global',
                    item.tags instanceof Collection ? item.tags.collect { it.toString() } : [],
                    item.source?.toString() ?: 'planner',
                    item.confidence instanceof Number ? item.confidence.doubleValue() : 0.7d
                )
                writes << KnowledgeSearchTool.toView(fact)
            }
        }
    }

    private void learnFromObservation(Map<String, Object> observation, List<Map<String, Object>> writes) {
        if (observation.tool == 'system.info' && observation.success && observation.output instanceof Map) {
            Map output = observation.output as Map
            [
                'local.os.family': output.osFamily,
                'local.os.name'  : output.osName,
                'local.user.home': output.userHome
            ].each { key, value ->
                if (value) {
                    AgentKnowledgeFact fact = knowledgeService.upsert(key, value.toString(), 'device', ['system', 'local'], 'system.info', 0.95d)
                    writes << KnowledgeSearchTool.toView(fact)
                }
            }
        }
        if (observation.tool == 'path.resolve' && observation.success && observation.output instanceof Map) {
            Map output = observation.output as Map
            if (output.exists == true && output.input && output.path) {
                AgentKnowledgeFact fact = knowledgeService.upsert(
                    "path.${output.input}".toString(),
                    output.path.toString(),
                    'device',
                    ['path', 'filesystem'],
                    'path.resolve',
                    0.85d
                )
                writes << KnowledgeSearchTool.toView(fact)
            }
        }
    }

    private void publishProgress(Map<String, Object> attributes,
                                 int step,
                                 String phase,
                                 String message,
                                 Map<String, Object> payload) {
        if (!attributes?.taskId) {
            return
        }
        Map<String, Object> body = [
            threadId: attributes.threadId,
            phase   : phase,
            step    : step
        ] as Map<String, Object>
        body.putAll(payload ?: [:])
        eventPublisher.publish(new DeviceTaskEvent(
            taskId: attributes.taskId?.toString(),
            userId: attributes.userId?.toString(),
            deviceId: attributes.deviceId?.toString(),
            type: DeviceProtocol.STATUS_AGENT_PROGRESS,
            status: DeviceProtocol.STATUS_RUNNING,
            level: phase == 'observe' && payload?.observation?.success == false ? 'warn' : 'info',
            message: message ?: phase,
            recoverable: false,
            payload: body.findAll { it.value != null } as Map<String, Object>
        ))
    }

    private static List<Map<String, Object>> mergeRoutes(List<Map<String, Object>> existing, List<Map<String, Object>> incoming) {
        Map<String, Map<String, Object>> merged = [:]
        (existing ?: []).each { if (it.id) merged[it.id.toString()] = it }
        (incoming ?: []).each { if (it.id) merged[it.id.toString()] = it }
        return merged.values().toList()
    }

    private Map<String, Object> fallbackAction(int step, String input) {
        if (step == 1) {
            return [tool: 'knowledge.search', args: [query: input ?: '', limit: properties.agent.maxKnowledgeResults]]
        }
        if (step == 2) {
            return [tool: 'system.info', args: [:]]
        }
        return [tool: null, args: [:]]
    }

    private Map tryParseJson(String response) {
        String json = extractJson(response ?: '')
        if (!json) {
            return null
        }
        try {
            return objectMapper.readValue(json, Map)
        } catch (Exception ignored) {
            return null
        }
    }

    private static String extractJson(String response) {
        String trimmed = response?.trim()
        if (!trimmed) {
            return ''
        }
        int start = trimmed.indexOf('{')
        int end = trimmed.lastIndexOf('}')
        return start >= 0 && end > start ? trimmed.substring(start, end + 1) : ''
    }
}
