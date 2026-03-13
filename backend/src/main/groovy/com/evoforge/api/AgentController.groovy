package com.evoforge.api

import com.evoforge.core.AgentService
import com.evoforge.router.SkillRouterService
import org.springframework.web.bind.annotation.*

@RestController
@RequestMapping('/api/agent')
class AgentController {
    private final AgentService agentService
    private final SkillRouterService routerService

    AgentController(AgentService agentService, SkillRouterService routerService) {
        this.agentService = agentService
        this.routerService = routerService
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
        }
        return response
    }
}
