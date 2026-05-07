package com.evoforge.router

import com.evoforge.api.SkillRouteCandidate
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
            return noSkill('No active skills available')
        }

        List<SkillRouteCandidate> candidates = retrieveCandidates(input, entries)
        if (candidates.isEmpty()) {
            return noSkill('No candidate skill metadata matched the task')
        }

        if (skillsConfig().routerUseLlm) {
            def llmDecision = routeWithLlm(input, candidates)
            if (llmDecision) {
                return llmDecision
            }
        }

        return routeHeuristic(candidates)
    }

    private SkillRouteDecision routeHeuristic(List<SkillRouteCandidate> candidates) {
        def top = candidates.first()
        double confidence = Math.min(1.0, 0.35 + top.score * 0.12)
        if (confidence < skillsConfig().routerMinConfidence) {
            return noSkill('Candidate confidence is below router threshold', candidates)
        }

        return new SkillRouteDecision(
            action: 'USE_SKILL',
            skillId: top.skillId,
            skillName: top.skillName,
            confidence: confidence,
            reason: 'Matched skill metadata',
            matched: top.matched,
            candidates: candidates
        )
    }

    private List<SkillRouteCandidate> retrieveCandidates(String input, List entries) {
        def normalized = (input ?: '').toLowerCase()
        Set<String> taskHints = inferTaskHints(normalized)
        def scored = entries.collect { entry ->
            def skill = entry.definition
            Map metadata = (skill?.metadata ?: [:]) as Map
            double score = 0.0
            List<String> matched = []

            if (skill?.name && normalized.contains(skill.name.toLowerCase())) {
                score += 4
                matched << "name:${skill.name}".toString()
            }
            def description = firstString(metadata, 'description', 'summary', 'purpose')
            score += scoreText('description', description, normalized, 0.6, matched)

            def keywords = listValue(metadata, 'keywords')
            keywords.each { kw ->
                if (kw && normalized.contains(kw.toString().toLowerCase())) {
                    score += 3
                    matched << "keyword:${kw}".toString()
                }
            }

            def tags = listValue(metadata, 'tags')
            tags.each { tag ->
                String normalizedTag = tag.toString().toLowerCase()
                if (normalized.contains(normalizedTag) || taskHints.contains(normalizedTag)) {
                    score += 2
                    matched << "tag:${tag}".toString()
                }
            }

            def triggerExamples = listValue(metadata, 'triggerExamples', 'trigger_examples', 'examples')
            triggerExamples.each { example ->
                score += scoreText('trigger', example?.toString(), normalized, 1.2, matched)
            }

            def antiTriggers = listValue(metadata, 'antiTriggers', 'anti_triggers')
            antiTriggers.each { anti ->
                if (anti && normalized.contains(anti.toString().toLowerCase())) {
                    score -= 4
                    matched << "anti:${anti}".toString()
                }
            }

            return new SkillRouteCandidate(
                skillId: skill.id,
                skillName: skill.name,
                score: Math.max(0.0, score),
                description: description,
                keywords: keywords,
                tags: tags,
                triggerExamples: triggerExamples,
                antiTriggers: antiTriggers,
                matched: uniqueStrings(matched)
            )
        }

        def config = skillsConfig()
        int limit = Math.max(1, config.routerCandidateLimit)
        return scored
            .findAll { it.score >= config.routerMinCandidateScore }
            .sort { a, b -> b.score <=> a.score }
            .take(limit)
    }

    private SkillRouteDecision routeWithLlm(String input, List<SkillRouteCandidate> candidates) {
        String prompt = buildPrompt(input, candidates)
        String response = modelHub.getLlm(null).chat(prompt, [:])
        def parsed = tryParse(response)
        if (!parsed) {
            return null
        }

        String action = normalizeAction(parsed.action, parsed.skillId)
        double confidence = parsed.confidence instanceof Number ? parsed.confidence.doubleValue() : 0.5
        if (confidence < skillsConfig().routerMinConfidence) {
            return null
        }

        if (action == 'NO_SKILL') {
            return new SkillRouteDecision(
                action: 'NO_SKILL',
                confidence: confidence,
                reason: parsed.reason?.toString() ?: 'LLM decided no skill is needed',
                matched: parsed.matched instanceof Collection ? parsed.matched.collect { it.toString() } : [],
                candidates: candidates
            )
        }

        if (action == 'NEEDS_NEW_SKILL') {
            return new SkillRouteDecision(
                action: 'NEEDS_NEW_SKILL',
                confidence: confidence,
                reason: parsed.reason?.toString() ?: 'LLM decided a new skill would help',
                matched: parsed.matched instanceof Collection ? parsed.matched.collect { it.toString() } : [],
                candidates: candidates,
                suggestedSkillName: parsed.suggestedSkillName?.toString(),
                suggestedSkillPurpose: parsed.suggestedSkillPurpose?.toString()
            )
        }

        def match = candidates.find { it.skillId == parsed.skillId }
        if (!match) {
            return null
        }

        return new SkillRouteDecision(
            action: 'USE_SKILL',
            skillId: match.skillId,
            skillName: match.skillName,
            confidence: confidence,
            reason: parsed.reason?.toString() ?: 'LLM routing',
            matched: parsed.matched instanceof Collection ? parsed.matched.collect { it.toString() } : [],
            candidates: candidates
        )
    }

    private Map<String, Object> tryParse(String response) {
        if (!response) {
            return null
        }
        String trimmed = extractJson(response.trim())
        if (!trimmed.startsWith('{')) {
            return null
        }
        try {
            return objectMapper.readValue(trimmed, Map)
        } catch (Exception ignored) {
            return null
        }
    }

    private String buildPrompt(String input, List<SkillRouteCandidate> candidates) {
        String candidateJson = objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(candidates)
        return """
You are a skill router. Decide whether the user task should use one existing skill, use no skill, or needs a new skill.
Only inspect the candidate metadata below. The full skill bodies are intentionally not loaded at routing time.
Return only JSON with keys:
- action: one of USE_SKILL, NO_SKILL, NEEDS_NEW_SKILL
- skillId: required only when action is USE_SKILL
- confidence: number from 0 to 1
- reason: short explanation
- matched: array of matched words or concepts
- suggestedSkillName: optional, only for NEEDS_NEW_SKILL
- suggestedSkillPurpose: optional, only for NEEDS_NEW_SKILL

Use NO_SKILL when the model can handle the task directly.
Use NEEDS_NEW_SKILL only when the task is likely to recur, needs a fixed workflow, project-specific rules, or bundled scripts/assets.

Input: ${input}
Candidate skills:\n${candidateJson}
""".stripIndent()
    }

    private SkillRouteDecision noSkill(String reason, List<SkillRouteCandidate> candidates = []) {
        return new SkillRouteDecision(
            action: 'NO_SKILL',
            confidence: 0.0,
            reason: reason,
            candidates: candidates
        )
    }

    private EvoForgeProperties.Skills skillsConfig() {
        return properties?.skills ?: new EvoForgeProperties.Skills()
    }

    private static String normalizeAction(Object rawAction, Object skillId) {
        String action = rawAction?.toString()?.trim()?.toUpperCase()
        if (!action && skillId) {
            return 'USE_SKILL'
        }
        if (action in ['USE_SKILL', 'NO_SKILL', 'NEEDS_NEW_SKILL']) {
            return action
        }
        return skillId ? 'USE_SKILL' : 'NO_SKILL'
    }

    private static String extractJson(String response) {
        if (response.startsWith('```')) {
            int firstBrace = response.indexOf('{')
            int lastBrace = response.lastIndexOf('}')
            if (firstBrace >= 0 && lastBrace > firstBrace) {
                return response.substring(firstBrace, lastBrace + 1)
            }
        }
        return response
    }

    private static double scoreText(String label, String value, String normalizedInput, double weight, List<String> matched) {
        if (!value) {
            return 0.0
        }
        String normalizedValue = value.toLowerCase()
        if (normalizedInput.contains(normalizedValue)) {
            matched << "${label}:${value}".toString()
            return weight * 3
        }
        double score = 0.0
        normalizedValue
            .split(/[^a-z0-9\u4e00-\u9fff._-]+/)
            .findAll { it?.size() >= 2 }
            .unique()
            .each { token ->
                if (normalizedInput.contains(token)) {
                    score += weight
                    matched << "${label}:${token}".toString()
                }
            }
        return score
    }

    private static Set<String> inferTaskHints(String normalizedInput) {
        Set<String> hints = [] as Set
        Map<String, List<String>> patterns = [
            spreadsheet : ['.xlsx', '.xls', '.csv', '.tsv', 'excel', 'spreadsheet', '表格'],
            presentation: ['.ppt', '.pptx', 'powerpoint', 'presentation', 'slides', '幻灯片'],
            document    : ['.doc', '.docx', 'word', 'document', '文档'],
            image       : ['.png', '.jpg', '.jpeg', '.webp', 'image', 'photo', '图片', '图像'],
            frontend    : ['frontend', 'ui', 'vue', 'react', 'flutter', '页面', '组件'],
            backend     : ['backend', 'api', 'database', 'sql', '接口', '后端']
        ]
        patterns.each { hint, values ->
            if (values.any { normalizedInput.contains(it) }) {
                hints << hint
            }
        }
        return hints
    }

    private static String firstString(Map metadata, String... keys) {
        for (String key : keys) {
            Object value = metadata[key]
            if (value) {
                return value.toString()
            }
        }
        return null
    }

    private static List<String> listValue(Map metadata, String... keys) {
        for (String key : keys) {
            Object value = metadata[key]
            if (value instanceof Collection) {
                return value.findAll { it != null && it.toString().trim() }.collect { it.toString() }
            }
            if (value) {
                return value.toString().split(',').collect { it.trim() }.findAll { it }
            }
        }
        return []
    }

    private static List<String> uniqueStrings(Collection values) {
        return values.findAll { it != null && it.toString().trim() }.collect { it.toString() }.unique()
    }
}
