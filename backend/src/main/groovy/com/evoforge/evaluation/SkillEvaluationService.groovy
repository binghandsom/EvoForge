package com.evoforge.evaluation

import com.evoforge.llm.ModelHub
import com.evoforge.model.SkillDefinition
import com.evoforge.model.SkillEvaluation
import com.evoforge.model.SkillResult
import com.fasterxml.jackson.databind.ObjectMapper
import org.springframework.stereotype.Service

@Service
class SkillEvaluationService {
    private final ModelHub modelHub
    private final ObjectMapper objectMapper

    SkillEvaluationService(ModelHub modelHub, ObjectMapper objectMapper) {
        this.modelHub = modelHub
        this.objectMapper = objectMapper
    }

    SkillEvaluation evaluate(SkillDefinition skill, String input, SkillResult result, String llm = null) {
        if (!result) {
            return new SkillEvaluation(score: 0.0, verdict: 'no_result', rationale: 'No result produced')
        }
        if (!result.success) {
            return new SkillEvaluation(score: 0.2, verdict: 'failed', rationale: result.error ?: 'Execution failed')
        }

        String prompt = buildPrompt(skill, input, result)
        String response = modelHub.getLlm(llm).chat(prompt, [:])
        def parsed = tryParse(response)
        if (parsed) {
            return new SkillEvaluation(
                score: (parsed.score instanceof Number ? parsed.score.doubleValue() : 0.6),
                verdict: parsed.verdict?.toString() ?: 'ok',
                rationale: parsed.rationale?.toString() ?: 'LLM evaluation',
                metrics: parsed.metrics instanceof Map ? parsed.metrics : [:]
            )
        }

        String outputText = result.output?.toString() ?: ''
        double score = outputText ? 0.7 : 0.4
        return new SkillEvaluation(score: score, verdict: 'heuristic', rationale: 'Fallback evaluation', metrics: [:])
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

    private String buildPrompt(SkillDefinition skill, String input, SkillResult result) {
        return """
You are evaluating a tool skill result.
Return JSON with keys: score (0-1), verdict, rationale, metrics (object).
Skill: ${skill?.name}
Input: ${input}
Output: ${result?.output}
""".stripIndent()
    }
}
