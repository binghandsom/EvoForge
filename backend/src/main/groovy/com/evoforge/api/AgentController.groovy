package com.evoforge.api

import com.evoforge.agent.AgentKnowledgeService
import com.evoforge.agent.AgentConversationMemoryService
import com.evoforge.agent.KnowledgeSearchTool
import com.evoforge.core.AgentService
import com.evoforge.router.SkillRouterService
import org.springframework.web.bind.annotation.*

@RestController
@RequestMapping('/api/agent')
class AgentController {
    private final AgentService agentService
    private final SkillRouterService routerService
    private final AgentKnowledgeService knowledgeService
    private final AgentConversationMemoryService conversationMemoryService

    AgentController(AgentService agentService,
                    SkillRouterService routerService,
                    AgentKnowledgeService knowledgeService,
                    AgentConversationMemoryService conversationMemoryService) {
        this.agentService = agentService
        this.routerService = routerService
        this.knowledgeService = knowledgeService
        this.conversationMemoryService = conversationMemoryService
    }

    @PostMapping('respond')
    AgentResponse respond(@RequestBody AgentRequest request) {
        return agentService.respond(request)
    }

    @PostMapping('route')
    AgentRouteResponse route(@RequestBody AgentRouteRequest request) {
        def decision = routerService.route(request.input)
        def response = new AgentRouteResponse(decision: decision)
        if (request.execute && decision?.skillId) {
            def result = agentService.respond(new AgentRequest(
                input: request.input,
                skillId: decision.skillId,
                llm: request.llm,
                codeModel: request.codeModel,
                attributes: request.attributes ?: [:]
            ))
            response.skillResult = result.skillResult
            response.output = result.output
        } else if (request.execute && decision?.action == 'NO_SKILL') {
            def result = agentService.respond(new AgentRequest(
                input: request.input,
                llm: request.llm,
                codeModel: request.codeModel,
                attributes: request.attributes ?: [:]
            ))
            response.output = result.output
        }
        return response
    }

    @GetMapping('knowledge')
    List<Map<String, Object>> searchKnowledge(@RequestParam(value = 'query', required = false) String query,
                                              @RequestParam(value = 'limit', required = false, defaultValue = '20') int limit) {
        return knowledgeService.search(query ?: '', limit).collect { KnowledgeSearchTool.toView(it) }
    }

    @PostMapping('knowledge')
    Map<String, Object> upsertKnowledge(@RequestBody Map<String, Object> request) {
        def fact = knowledgeService.upsert(
            request.key?.toString(),
            request.value?.toString(),
            request.scope?.toString() ?: 'global',
            request.tags instanceof Collection ? request.tags.collect { it.toString() } : [],
            request.source?.toString() ?: 'api',
            request.confidence instanceof Number ? request.confidence.doubleValue() : 0.7d
        )
        return KnowledgeSearchTool.toView(fact)
    }

    @GetMapping('conversations')
    List<Map<String, Object>> conversations(@RequestParam(value = 'limit', required = false, defaultValue = '50') int limit) {
        return conversationMemoryService.threadViews(conversationMemoryService.listThreads(limit))
    }

    @PostMapping('conversations')
    Map<String, Object> createConversation(@RequestBody(required = false) Map<String, Object> request) {
        def thread = conversationMemoryService.createThread(
            request?.threadId?.toString(),
            request?.title?.toString(),
            request?.metadata instanceof Map ? request.metadata as Map<String, Object> : [:]
        )
        return conversationMemoryService.threadView(thread)
    }

    @GetMapping('conversations/{threadId}/turns')
    List<Map<String, Object>> conversationTurns(@PathVariable('threadId') String threadId,
                                                @RequestParam(value = 'limit', required = false, defaultValue = '100') int limit) {
        return conversationMemoryService.toView(conversationMemoryService.recent(threadId, limit))
    }
}
