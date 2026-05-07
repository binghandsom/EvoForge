package com.evoforge.core

import com.evoforge.agent.AgentRuntimeService
import com.evoforge.agent.AgentConversationMemoryService
import com.evoforge.agent.AgentConversationTurn
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
    private final AgentConversationMemoryService conversationMemoryService
    private final EvoForgeProperties properties

    AgentService(ModelHub modelHub,
                 SkillService skillService,
                 AgentRuntimeService agentRuntimeService,
                 AgentConversationMemoryService conversationMemoryService,
                 EvoForgeProperties properties) {
        this.modelHub = modelHub
        this.skillService = skillService
        this.agentRuntimeService = agentRuntimeService
        this.conversationMemoryService = conversationMemoryService
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
        Map<String, Object> attributes = new LinkedHashMap<>(request.attributes ?: [:])
        String threadId = conversationMemoryService.resolveThreadId(attributes)
        attributes.threadId = threadId
        List<AgentConversationTurn> context = conversationMemoryService.recent(threadId, properties.agent.maxThreadTurns)
        if ((request.input ?: '').trim()) {
            conversationMemoryService.append(threadId, 'user', request.input ?: '', [
                source: 'direct-chat'
            ] as Map<String, Object>)
        }
        def reply = modelHub.getLlm(request.llm).chat(buildDirectChatPrompt(request.input ?: '', context), attributes)
        if ((reply ?: '').trim()) {
            conversationMemoryService.append(threadId, 'assistant', reply, [
                source: 'direct-chat'
            ] as Map<String, Object>)
        }
        return new AgentResponse(output: reply)
    }

    private String buildDirectChatPrompt(String input, List<AgentConversationTurn> context) {
        return """
Use the same-thread conversation memory to understand follow-up instructions. The latest user message wins if it corrects earlier context.

Thread memory before this user message:
${conversationMemoryService.formatForPrompt(context)}

Current user message:
${input ?: ''}
""".stripIndent()
    }
}
