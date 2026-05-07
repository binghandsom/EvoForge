package com.evoforge.agent

import com.evoforge.core.EvoForgeProperties
import com.evoforge.llm.ModelHub
import com.fasterxml.jackson.databind.ObjectMapper
import org.springframework.stereotype.Service

@Service
class AgentRuntimeService {
    private final ModelHub modelHub
    private final AgentToolRegistry toolRegistry
    private final AgentKnowledgeService knowledgeService
    private final EvoForgeProperties properties
    private final ObjectMapper objectMapper

    AgentRuntimeService(ModelHub modelHub,
                        AgentToolRegistry toolRegistry,
                        AgentKnowledgeService knowledgeService,
                        EvoForgeProperties properties,
                        ObjectMapper objectMapper) {
        this.modelHub = modelHub
        this.toolRegistry = toolRegistry
        this.knowledgeService = knowledgeService
        this.properties = properties
        this.objectMapper = objectMapper
    }

    AgentRunResult run(String input, Map<String, Object> attributes = [:]) {
        List<Map<String, Object>> observations = []
        List<Map<String, Object>> routes = []
        List<Map<String, Object>> writes = []
        AgentToolContext context = new AgentToolContext(
            taskId: attributes?.taskId?.toString(),
            userId: attributes?.userId?.toString(),
            deviceId: attributes?.deviceId?.toString(),
            attributes: attributes ?: [:]
        )

        int maxSteps = Math.max(1, Math.min(properties.agent.maxSteps, 20))
        for (int step = 1; step <= maxSteps; step++) {
            AgentPlannerTurn turn = plan(input, observations, routes, attributes ?: [:])
            routes = mergeRoutes(routes, turn.routes)
            persistKnowledge(turn.knowledgeWrites, writes)

            if (turn.finalAnswer) {
                return new AgentRunResult(
                    success: true,
                    output: turn.finalAnswer,
                    routes: routes,
                    observations: observations,
                    knowledgeWrites: writes,
                    stopReason: 'final'
                )
            }

            Map<String, Object> action = turn.action ?: fallbackAction(step, input)
            if (!action?.tool) {
                return new AgentRunResult(
                    success: false,
                    output: '没有可执行的下一步动作。',
                    routes: routes,
                    observations: observations,
                    knowledgeWrites: writes,
                    stopReason: 'no-action'
                )
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
            learnFromObservation(observation, writes)
        }

        String answer = summarize(input, routes, observations, attributes ?: [:])
        return new AgentRunResult(
            success: true,
            output: answer,
            routes: routes,
            observations: observations,
            knowledgeWrites: writes,
            stopReason: 'max-steps'
        )
    }

    private AgentPlannerTurn plan(String input,
                                  List<Map<String, Object>> observations,
                                  List<Map<String, Object>> routes,
                                  Map<String, Object> attributes) {
        String prompt = buildPlannerPrompt(input, observations, routes)
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
            finalAnswer: parsed.finalAnswer?.toString(),
            knowledgeWrites: parsed.knowledgeWrites instanceof Collection ? parsed.knowledgeWrites.collect { it as Map<String, Object> } : []
        )
    }

    private String buildPlannerPrompt(String input,
                                      List<Map<String, Object>> observations,
                                      List<Map<String, Object>> routes) {
        def facts = knowledgeService.search(input ?: '', properties.agent.maxKnowledgeResults).collect { KnowledgeSearchTool.toView(it) }
        return """
You are EvoForge's task planner. You do not execute operations yourself; you choose tools.
The system is intentionally dynamic: produce multiple possible routes, execute one low-risk next action, observe the result, then adapt.
Prefer stable reusable knowledge, but verify cheap facts when they may drift. When a route fails, preserve the failure and try another plausible route.

Return only JSON:
{
  "thought": "short reasoning",
  "routes": [
    {"id":"A","status":"candidate|active|blocked|done","rationale":"why this route may work","next":"short next step"}
  ],
  "selectedRouteId": "A",
  "action": {"tool":"tool.name","args":{}},
  "finalAnswer": null,
  "knowledgeWrites": [
    {"key":"stable.fact.key","value":"fact value","scope":"global","tags":["tag"],"confidence":0.8,"source":"planner"}
  ]
}

Rules:
- Return finalAnswer only when the user task is genuinely answered or you need a specific clarification from the user.
- Do not invent filesystem facts. Use system.info, shell.run, path.resolve, and file.list.
- Use image.analyze only after you have concrete readable image paths.
- Use knowledge.upsert or knowledgeWrites for reusable facts only, not temporary outputs.
- Prefer argv arrays for shell.run. Avoid destructive commands.

User task:
${input ?: ''}

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
                             Map<String, Object> attributes) {
        String prompt = """
Summarize the current EvoForge agent run for the user.
Be concrete about what was tried, what worked, what failed, and the next actionable path.

Task: ${input ?: ''}
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

    private void persistKnowledge(List<Map<String, Object>> items, List<Map<String, Object>> writes) {
        (items ?: []).each { item ->
            if (!item?.key || !item?.value) {
                return
            }
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
