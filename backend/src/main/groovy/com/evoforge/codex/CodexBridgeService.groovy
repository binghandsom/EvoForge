package com.evoforge.codex

import com.evoforge.agent.ProjectKnowledgeContext
import com.evoforge.agent.ProjectKnowledgeContextService
import com.evoforge.model.SkillDefinition
import com.evoforge.model.SkillStatus
import com.evoforge.store.SkillStore
import com.evoforge.tester.EvoForgeTesterService
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.stereotype.Service

import java.util.Locale

@Service
class CodexBridgeService {
    private final ProjectKnowledgeContextService contextService
    private final SkillStore skillStore
    private final EvoForgeTesterService testerService
    private final CodexQuestionBridgeService questionBridgeService

    CodexBridgeService(ProjectKnowledgeContextService contextService,
                       SkillStore skillStore) {
        this(contextService, skillStore, null, null)
    }

    @Autowired
    CodexBridgeService(ProjectKnowledgeContextService contextService,
                       SkillStore skillStore,
                       EvoForgeTesterService testerService,
                       CodexQuestionBridgeService questionBridgeService) {
        this.contextService = contextService
        this.skillStore = skillStore
        this.testerService = testerService
        this.questionBridgeService = questionBridgeService
    }

    Map<String, Object> manifest(String projectKey, String baseUrl) {
        String safeBaseUrl = normalizeBaseUrl(baseUrl)
        String safeProjectKey = text(projectKey) ?: '<projectKey>'
        return [
            name          : 'EvoForge Codex Bridge',
            version       : '1',
            projectKey    : projectKey,
            retrieval     : [
                endpoint       : "${safeBaseUrl}/api/codex/bridge/query".toString(),
                mode           : 'focused-hybrid',
                vectorReady    : false,
                vectorContract : 'The bridge API is vector-ready; the current backend ranks project-scoped facts with hybrid lexical, tag, confidence, latest-change, error, correction, and task-intent signals.'
            ],
            skillCatalog  : [
                endpoint: "${safeBaseUrl}/api/codex/bridge/skills".toString(),
                detail  : "${safeBaseUrl}/api/codex/bridge/skills/{id}".toString(),
                execute : "${safeBaseUrl}/api/skills/{id}/execute".toString()
            ],
            tester       : testerService
                ? testerService.capability(safeBaseUrl, projectKey)
                : [
                    enabled      : false,
                    planEndpoint : "${safeBaseUrl}/api/codex/bridge/test-plan".toString(),
                    runEndpoint  : "${safeBaseUrl}/api/codex/bridge/test-run".toString(),
                    commandSource: 'configured-whitelist'
                ],
            humanInput   : questionBridgeService
                ? questionBridgeService.capability(safeBaseUrl)
                : [
                    endpoint: "${safeBaseUrl}/api/codex/bridge/questions/ask".toString(),
                    responseType: 'human_response'
                ],
            suggestedCalls: [
                [
                    purpose: 'Inspect available bridge capabilities',
                    command: "curl -s '${safeBaseUrl}/api/codex/bridge/manifest?projectKey=${safeProjectKey}'"
                ],
                [
                    purpose: 'Ask EvoForge for focused project context and matching skills',
                    command: """curl -s -X POST '${safeBaseUrl}/api/codex/bridge/query' -H 'Content-Type: application/json' -d '{"projectKey":"${safeProjectKey}","query":"<current question>","includeContext":true,"includeSkills":true}'"""
                ],
                [
                    purpose: 'Search the active skill catalog',
                    command: "curl -s '${safeBaseUrl}/api/codex/bridge/skills?query=<task>&limit=5'"
                ],
                [
                    purpose: 'Ask EvoForge tester for a focused test plan',
                    command: """curl -s -X POST '${safeBaseUrl}/api/codex/bridge/test-plan' -H 'Content-Type: application/json' -d '{"projectKey":"${safeProjectKey}","task":"<current task>"}'"""
                ],
                [
                    purpose: 'Ask the mobile user a blocking question and wait for their response',
                    command: """curl -s -X POST '${safeBaseUrl}/api/codex/bridge/questions/ask' -H 'Content-Type: application/json' -d '{"projectKey":"${safeProjectKey}","taskId":"<current task id>","question":"<question for the user>","options":[]}'"""
                ]
            ],
            usageRules    : [
                'Call the bridge only when project memory or EvoForge skills could reduce uncertainty.',
                'Prefer small, task-specific queries over broad dumps.',
                'Treat returned memory as supporting evidence; verify against repository files before editing.',
                'Use skill summaries to decide whether a skill is relevant before calling /api/skills/{id}/execute.',
                'Use the tester plan before running tests; tester execution is limited to configured command ids.',
                'When blocked on product intent or permission, call the humanInput endpoint instead of guessing.'
            ]
        ].findAll { it.value != null } as Map<String, Object>
    }

    Map<String, Object> query(Map request, String baseUrl) {
        String projectKey = text(request?.projectKey)
        String query = text(request?.query ?: request?.input)
        Map learning = [
            contextPolicy: request?.contextPolicy instanceof Map ? request.contextPolicy : [:]
        ]
        ProjectKnowledgeContext context = request?.includeContext == false
            ? ProjectKnowledgeContext.empty()
            : contextService.build(projectKey, query, learning)
        List<Map<String, Object>> skills = request?.includeSkills == false
            ? []
            : searchSkills(query, safeInt(request?.skillLimit, 6, 1, 20), true)

        return [
            bridge       : 'evoforge-codex-bridge',
            retrievalMode: request?.retrievalMode ?: 'focused-hybrid',
            vectorReady  : false,
            projectKey   : projectKey,
            query        : query,
            context      : [
                count      : context.entries?.size() ?: 0,
                maxFacts   : context.maxFacts,
                maxChars   : context.maxChars,
                promptBlock: context.toPromptBlock(),
                entries    : context.entries.collect { entry ->
                    [
                        key       : entry.key,
                        source    : entry.source,
                        confidence: entry.confidence,
                        score     : entry.score,
                        reason    : entry.reason,
                        excerpt   : entry.excerpt
                    ]
                }
            ],
            skills       : skills,
            usageHint    : 'Use context entries as hints only; ask another focused query if a different slice is needed.'
        ] as Map<String, Object>
    }

    List<Map<String, Object>> searchSkills(String query, int limit = 8, boolean enabledOnly = true) {
        String normalized = lower(query)
        List<String> terms = terms(query)
        return skillStore.loadAllSummaries()
            .findAll { SkillDefinition skill -> !enabledOnly || (skill.enabled && skill.status == SkillStatus.ACTIVE) }
            .collect { SkillDefinition skill -> [skill: skill, score: skillScore(skill, normalized, terms)] }
            .findAll { !normalized || it.score > 0 }
            .sort { a, b -> b.score <=> a.score ?: a.skill.name <=> b.skill.name }
            .take(Math.max(1, Math.min(limit, 50)))
            .collect { toSkillCard(it.skill as SkillDefinition, it.score as double, false) }
    }

    Map<String, Object> skillDetail(String id, String baseUrl) {
        SkillDefinition skill = skillStore.findById(id).orElseThrow { new IllegalArgumentException("Skill not found: ${id}") }
        return toSkillCard(skill, 0d, true, normalizeBaseUrl(baseUrl))
    }

    private static Map<String, Object> toSkillCard(SkillDefinition skill,
                                                   double score,
                                                   boolean detail,
                                                   String baseUrl = '') {
        Map metadata = (skill.metadata ?: [:]) as Map
        String endpoint = baseUrl ? "${baseUrl}/api/skills/${skill.id}/execute".toString() : "/api/skills/${skill.id}/execute".toString()
        Map<String, Object> card = [
            id             : skill.id,
            name           : skill.name,
            version        : skill.version,
            enabled        : skill.enabled,
            status         : skill.status?.toString(),
            score          : score,
            description    : firstString(metadata, 'description', 'summary', 'purpose'),
            keywords       : listValue(metadata, 'keywords'),
            tags           : listValue(metadata, 'tags'),
            triggerExamples: listValue(metadata, 'triggerExamples', 'trigger_examples', 'examples'),
            antiTriggers   : listValue(metadata, 'antiTriggers', 'anti_triggers'),
            usage          : firstString(metadata, 'usage', 'howToUse', 'inputContract') ?: "POST ${endpoint} with JSON {\"input\":\"...\",\"attributes\":{}}",
            execute        : [
                method     : 'POST',
                endpoint   : endpoint,
                requestBody: [input: '<task input>', attributes: [:]]
            ]
        ].findAll { it.value != null && it.value != '' } as Map<String, Object>
        if (detail) {
            card.metadata = metadata
            card.entryClass = skill.entryClass
            card.language = skill.language
            card.updatedAt = skill.updatedAt?.toString()
        }
        return card
    }

    private static double skillScore(SkillDefinition skill, String normalizedQuery, List<String> queryTerms) {
        if (!normalizedQuery) {
            return skill.enabled && skill.status == SkillStatus.ACTIVE ? 1.0d : 0d
        }
        Map metadata = (skill.metadata ?: [:]) as Map
        String text = lower([
            skill.id,
            skill.name,
            firstString(metadata, 'description', 'summary', 'purpose'),
            listValue(metadata, 'keywords').join(' '),
            listValue(metadata, 'tags').join(' '),
            listValue(metadata, 'triggerExamples', 'trigger_examples', 'examples').join(' ')
        ].findAll { it }.join(' '))
        double score = 0d
        if (skill.name && normalizedQuery.contains(lower(skill.name))) score += 6d
        queryTerms.each { term ->
            if (text.contains(term)) {
                score += term.length() >= 4 ? 2.5d : 1.5d
            }
        }
        listValue(metadata, 'keywords').each { keyword ->
            if (keyword && normalizedQuery.contains(lower(keyword))) {
                score += 4d
            }
        }
        listValue(metadata, 'antiTriggers', 'anti_triggers').each { anti ->
            if (anti && normalizedQuery.contains(lower(anti))) {
                score -= 5d
            }
        }
        if (skill.enabled && skill.status == SkillStatus.ACTIVE) score += 1d
        return Math.max(0d, score)
    }

    private static List<String> terms(String value) {
        return lower(value).split('[^a-z0-9\\u4e00-\\u9fff._/-]+').findAll { it && it.length() >= 2 }.unique()
    }

    private static List<String> listValue(Map metadata, String... keys) {
        Object value = keys.collect { metadata[it] }.find { it != null }
        if (value instanceof Collection) {
            return value.collect { text(it) }.findAll { it }.unique()
        }
        if (value) {
            return [text(value)].findAll { it }
        }
        return []
    }

    private static String firstString(Map metadata, String... keys) {
        Object value = keys.collect { metadata[it] }.find { it != null && text(it) }
        return text(value)
    }

    private static int safeInt(Object raw, int fallback, int min, int max) {
        int value
        try {
            value = raw == null ? fallback : raw.toString().toInteger()
        } catch (Exception ignored) {
            value = fallback
        }
        return Math.max(min, Math.min(max, value))
    }

    private static String normalizeBaseUrl(String baseUrl) {
        String value = text(baseUrl) ?: 'http://localhost:18080'
        return value.endsWith('/') ? value.substring(0, value.length() - 1) : value
    }

    private static String lower(Object value) {
        return (value ?: '').toString().toLowerCase(Locale.ROOT)
    }

    private static String text(Object value) {
        return value == null ? '' : value.toString().trim()
    }
}
