package com.evoforge.core

import com.evoforge.api.AgentRequest
import com.evoforge.api.AgentResponse
import com.evoforge.llm.ModelHub
import com.evoforge.skills.SkillService
import org.springframework.stereotype.Service

@Service
class AgentService {
    private final ModelHub modelHub
    private final SkillService skillService

    AgentService(ModelHub modelHub, SkillService skillService) {
        this.modelHub = modelHub
        this.skillService = skillService
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
        def reply = modelHub.getLlm(request.llm).chat(request.input ?: '', request.attributes ?: [:])
        return new AgentResponse(output: reply)
    }
}
