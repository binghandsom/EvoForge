package com.evoforge.router

import com.evoforge.api.SkillRouteDecision
import com.evoforge.core.EvoForgeProperties
import com.evoforge.llm.ModelHub
import com.evoforge.skills.SkillRegistry
import com.fasterxml.jackson.databind.ObjectMapper
import org.springframework.stereotype.Service

@Service
class SkillRouterService {
    private final SkillRegistry registry
    private final ModelHub modelHub
    private final EvoForgeProperties properties
    private final ObjectMapper objectMapper

    SkillRouterService(SkillRegistry registry, ModelHub modelHub, EvoForgeProperties properties, ObjectMapper objectMapper) {
        this.registry = registry
        this.modelHub = modelHub
        this.properties = properties
        this.objectMapper = objectMapper
    }

    SkillRouteDecision route(String input) {
        def entries = registry.list()
        if (entries.isEmpty()) {
            return new SkillRouteDecision(
                skillId: null,
                skillName: null,
                confidence: 0.0,
                reason: 'No active skills available'
            )
        }

        if (properties.skills.routerUseLlm) {
            def llmDecision = routeWithLlm(input, entries)
            if (llmDecision) {
                return llmDecision
            }
        }

        return routeHeuristic(input, entries)
    }

    private SkillRouteDecision routeHeuristic(String input, List entries) {
        def normalized = (input ?: '').toLowerCase()
        def scored = entries.collect { entry ->
            def skill = entry.definition
            double score = 0
            List<String> matched = []
            if (skill?.name && normalized.contains(skill.name.toLowerCase())) {
                score += 2
                matched << skill.name
            }
            def keywords = (skill?.metadata?.keywords instanceof Collection) ? skill.metadata.keywords : []
            keywords.each { kw ->
                if (kw && normalized.contains(kw.toString().toLowerCase())) {
                    score += 1
                    matched << kw.toString()
                }
            }
            return [entry: entry, score: score, matched: matched]
        }

        scored.sort { -it.score }
        def top = scored.first()
        def skill = top.entry.definition
        double confidence = top.score > 0 ? Math.min(1.0, 0.4 + top.score * 0.2) : 0.2
        String reason = top.score > 0 ? 'Matched name/keywords' : 'Defaulted to first active skill'

        return new SkillRouteDecision(
            skillId: skill.id,
            skillName: skill.name,
            confidence: confidence,
            reason: reason,
            matched: top.matched
        )
    }

    private SkillRouteDecision routeWithLlm(String input, List entries) {
        String prompt = buildPrompt(input, entries)
        String response = modelHub.getLlm(null).chat(prompt, [:])
        def parsed = tryParse(response)
        if (!parsed?.skillId) {
            return null
        }
        def match = entries.find { it.definition?.id == parsed.skillId }
        if (!match) {
            return null
        }
        double confidence = parsed.confidence instanceof Number ? parsed.confidence.doubleValue() : 0.5
        if (confidence < properties.skills.routerMinConfidence) {
            return null
        }
        return new SkillRouteDecision(
            skillId: match.definition.id,
            skillName: match.definition.name,
            confidence: confidence,
            reason: parsed.reason?.toString() ?: 'LLM routing',
            matched: parsed.matched instanceof Collection ? parsed.matched.collect { it.toString() } : []
        )
    }

    private Map<String, Object> tryParse(String response) {
        if (!response) {
            return null
        }
        String trimmed = response.trim()
        if (!trimmed.startsWith('{')) {
            return null
        }
        try {
            return objectMapper.readValue(trimmed, Map)
        } catch (Exception ignored) {
            return null
        }
    }

    private String buildPrompt(String input, List entries) {
        def lines = entries.collect { entry ->
            def skill = entry.definition
            def keywords = (skill?.metadata?.keywords instanceof Collection) ? skill.metadata.keywords.join(',') : ''
            return "- id: ${skill.id}, name: ${skill.name}, keywords: ${keywords}"
        }.join('\n')
        return """
You are a router. Choose the best skill for the input.
Return JSON with keys: skillId, confidence (0-1), reason, matched (array).
Input: ${input}
Skills:\n${lines}
""".stripIndent()
    }
}
