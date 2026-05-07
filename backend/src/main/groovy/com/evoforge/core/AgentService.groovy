package com.evoforge.core

import com.evoforge.agent.AgentRuntimeService
import com.evoforge.api.AgentRequest
import com.evoforge.api.AgentResponse
import com.evoforge.llm.ModelHub
import com.evoforge.skills.SkillService
import org.springframework.stereotype.Service

@Service
class AgentService {
    private final ModelHub modelHub
    private final SkillService skillService
    private final AgentRuntimeService agentRuntimeService
    private final EvoForgeProperties properties

    AgentService(ModelHub modelHub,
                 SkillService skillService,
                 AgentRuntimeService agentRuntimeService,
                 EvoForgeProperties properties) {
        this.modelHub = modelHub
        this.skillService = skillService
        this.agentRuntimeService = agentRuntimeService
        this.properties = properties
    }

    AgentResponse respond(AgentRequest request) {
        if (request.skillId) {
            boolean evaluate = request.attributes?.evaluate == true
            def result = skillService.execute(
                request.skillId,
                request.input,
                request.attributes ?: [:],
                request.llm,
                request.codeModel,
                evaluate
            )
            return new AgentResponse(output: result.output?.toString(), skillResult: result)
        }
        if (properties.agent.enabled && request.attributes?.directChat != true) {
            Map<String, Object> attributes = new LinkedHashMap<>(request.attributes ?: [:])
            attributes.llm = request.llm
            attributes.codeModel = request.codeModel
            def run = agentRuntimeService.run(request.input ?: '', attributes)
            return new AgentResponse(output: run.output, agentRun: run)
        }
        def reply = modelHub.getLlm(request.llm).chat(request.input ?: '', request.attributes ?: [:])
        return new AgentResponse(output: reply)
    }
}
