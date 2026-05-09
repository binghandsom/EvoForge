package com.evoforge.agent

import com.fasterxml.jackson.databind.ObjectMapper
import org.springframework.stereotype.Service

import java.time.Instant
import java.util.Locale

@Service
class ProjectKnowledgeContextService {
    private final AgentKnowledgeService knowledgeService
    private final ObjectMapper objectMapper

    ProjectKnowledgeContextService(AgentKnowledgeService knowledgeService,
                                   ObjectMapper objectMapper) {
        this.knowledgeService = knowledgeService
        this.objectMapper = objectMapper
    }

    ProjectKnowledgeContext build(String projectKey, String userTask, Map learning = [:]) {
        String sourceProjectKey = text(projectKey)
        if (!sourceProjectKey) {
            return ProjectKnowledgeContext.empty()
        }

        ProjectKnowledgeContextPolicy policy = ProjectKnowledgeContextPolicy.from(learning?.contextPolicy)
        ProjectKnowledgeIntent intent = ProjectKnowledgeIntent.from(userTask)
        String scope = "project:${sourceProjectKey}".toString()

        List<AgentKnowledgeFact> candidates = []
        candidates.addAll(knowledgeService.search("${sourceProjectKey} ${userTask ?: ''}".toString(), 40))
        candidates.addAll(knowledgeService.search("${sourceProjectKey} latest-change project-facts architecture skills errors correction".toString(), 30))
        knowledgeService.findByKey("project.${sourceProjectKey}.latest_change".toString(), scope).ifPresent { candidates << it }
        if (intent.errorFocused) {
            candidates.addAll(knowledgeService.search("${sourceProjectKey} error failed bug 异常 报错 修复".toString(), 20))
        }
        if (intent.correctionFocused) {
            candidates.addAll(knowledgeService.search("${sourceProjectKey} correction wrong incorrect 不对 修正 纠正".toString(), 20))
        }
        if (intent.skillFocused) {
            candidates.addAll(knowledgeService.search("${sourceProjectKey} skill skills tool capability 技能 工具 能力".toString(), 20))
        }

        List<ProjectKnowledgeContextEntry> ranked = candidates
            .findAll { matchesProject(it, sourceProjectKey, scope) }
            .groupBy { "${it.scope ?: 'global'}:${it.key}".toString() }
            .collect { ignored, facts -> facts.max { AgentKnowledgeFact fact -> fact.updatedAt ?: Instant.EPOCH } as AgentKnowledgeFact }
            .collect { fact -> buildEntry(fact, sourceProjectKey, userTask, intent, policy) }
            .findAll { it.score > 0 && it.excerpt }
            .sort { a, b -> b.score <=> a.score ?: b.confidence <=> a.confidence ?: a.key <=> b.key }

        List<ProjectKnowledgeContextEntry> selected = []
        Set<String> seenExcerpts = [] as Set<String>
        int usedChars = 0
        for (ProjectKnowledgeContextEntry entry : ranked) {
            if (selected.size() >= policy.maxFacts) {
                break
            }
            String fingerprint = normalizeFingerprint(entry.excerpt)
            if (fingerprint && seenExcerpts.contains(fingerprint)) {
                continue
            }
            int remaining = policy.maxChars - usedChars
            if (remaining < Math.min(240, policy.maxCharsPerFact) && selected) {
                break
            }
            String excerpt = compact(entry.excerpt, Math.min(policy.maxCharsPerFact, Math.max(240, remaining)))
            selected << entry.copyWithExcerpt(excerpt)
            seenExcerpts << fingerprint
            usedChars += excerpt.length()
        }

        return new ProjectKnowledgeContext(
            projectKey: sourceProjectKey,
            entries: selected,
            maxFacts: policy.maxFacts,
            maxChars: policy.maxChars
        )
    }

    private ProjectKnowledgeContextEntry buildEntry(AgentKnowledgeFact fact,
                                                    String projectKey,
                                                    String userTask,
                                                    ProjectKnowledgeIntent intent,
                                                    ProjectKnowledgeContextPolicy policy) {
        List<String> tags = (fact.tags ?: []).collect { text(it) }.findAll { it }
        Map value = parseValue(fact.value)
        int score = score(fact, value, tags, projectKey, userTask, intent)
        List<String> reasons = reasons(fact, value, tags, userTask, intent)
        return new ProjectKnowledgeContextEntry(
            key: fact.key,
            source: fact.source ?: 'unknown',
            confidence: fact.confidence,
            tags: tags,
            score: score,
            reason: reasons.take(4).join(', '),
            excerpt: excerpt(fact, value, policy.maxCharsPerFact)
        )
    }

    private static int score(AgentKnowledgeFact fact,
                             Map value,
                             List<String> tags,
                             String projectKey,
                             String userTask,
                             ProjectKnowledgeIntent intent) {
        int score = 0
        String key = lower(fact.key)
        String tagText = lower(tags.join(' '))
        String source = lower(fact.source)
        String haystack = lower("${fact.key} ${tags.join(' ')} ${fact.value ?: ''}".toString())

        if (fact.scope == "project:${projectKey}".toString()) score += 45
        if (tags.contains(projectKey)) score += 18
        if (key.endsWith('.latest_change') || tags.contains('latest-change')) score += 35
        if (tags.contains('project-facts')) score += 16
        if (tagText.contains('architecture') && intent.architectureFocused) score += 18
        if ((tagText.contains('skill') || tagText.contains('tool')) && intent.skillFocused) score += 18
        if (tagText.contains('error')) score += intent.errorFocused ? 22 : -8
        if (tagText.contains('correction-candidate')) score += intent.correctionFocused ? 24 : -10
        if ((tagText.contains('change-lineage') || key.contains('.change.')) && intent.lineageFocused) score += 16
        if (source == 'codex-learning' && !intent.lineageFocused && !intent.errorFocused) score -= 4
        score += Math.round(Math.max(0d, Math.min(1d, fact.confidence)) * 10d) as int

        terms(userTask).take(24).each { term ->
            if (haystack.contains(term)) {
                score += term.length() >= 4 ? 5 : 3
            }
        }
        String status = lower(value.status)
        if (status == 'failed' && intent.errorFocused) score += 12
        return score
    }

    private static List<String> reasons(AgentKnowledgeFact fact,
                                        Map value,
                                        List<String> tags,
                                        String userTask,
                                        ProjectKnowledgeIntent intent) {
        List<String> reasons = []
        String key = lower(fact.key)
        String tagText = lower(tags.join(' '))
        if (key.endsWith('.latest_change') || tags.contains('latest-change')) reasons << 'latest project change'
        if ((tagText.contains('change-lineage') || key.contains('.change.')) && intent.lineageFocused) reasons << 'task asks for change lineage'
        if (tagText.contains('error') && intent.errorFocused) reasons << 'task is error/fix oriented'
        if (tagText.contains('correction-candidate') && intent.correctionFocused) reasons << 'task may correct prior knowledge'
        if ((tagText.contains('skill') || tagText.contains('tool')) && intent.skillFocused) reasons << 'task mentions skills/tools'
        if (tagText.contains('architecture') && intent.architectureFocused) reasons << 'task asks for architecture'
        if (terms(userTask).any { term -> lower("${fact.key} ${tags.join(' ')} ${fact.value ?: ''}".toString()).contains(term) }) {
            reasons << 'matched current task terms'
        }
        if (value.userTask) reasons << 'has prior user intent'
        return reasons ?: ['project-scoped high-confidence fact']
    }

    private String excerpt(AgentKnowledgeFact fact, Map value, int limit) {
        List<String> parts = []
        if (value) {
            if (value.kind) parts << "kind=${value.kind}"
            if (value.userTask) parts << "task=${compact(value.userTask, 280)}"
            if (value.status) parts << "status=${value.status}"
            if (value.message) parts << "message=${compact(value.message, 220)}"
            if (value.codexOutput) parts << "result=${compact(value.codexOutput, 420)}"
            if (value.evidence) parts << "evidence=${compact(value.evidence, 360)}"
            if (value.previousChange instanceof Map && value.previousChange.userTask) {
                parts << "previous=${compact(value.previousChange.userTask, 220)}"
            }
            if (value.recordedAt) parts << "recordedAt=${value.recordedAt}"
        }
        String text = parts ? parts.join(' | ') : (fact.value ?: '')
        return compact(text, limit)
    }

    private Map parseValue(String value) {
        if (!value) {
            return [:]
        }
        try {
            Object parsed = objectMapper.readValue(value, Object)
            return parsed instanceof Map ? parsed as Map : [:]
        } catch (Exception ignored) {
            return [:]
        }
    }

    private static boolean matchesProject(AgentKnowledgeFact fact, String projectKey, String scope) {
        return fact?.scope == scope || (fact?.tags ?: []).contains(projectKey)
    }

    private static List<String> terms(String text) {
        String normalized = lower(text)
        List<String> raw = normalized.split('[^a-z0-9\\u4e00-\\u9fff._/-]+').findAll { it && it.length() >= 2 }
        List<String> result = []
        raw.each { token ->
            result << token
            if (token.length() > 8 && token ==~ /.*[\u4e00-\u9fff].*/) {
                for (int index = 0; index <= token.length() - 2 && result.size() < 40; index += 2) {
                    result << token.substring(index, index + 2)
                }
            }
        }
        return result.unique()
    }

    private static String normalizeFingerprint(String value) {
        return compact(value, 260).toLowerCase(Locale.ROOT).replaceAll('\\s+', ' ')
    }

    private static String compact(Object value, int limit) {
        String normalized = (value ?: '').toString().replaceAll('\\s+', ' ').trim()
        return normalized.length() > limit ? normalized.take(Math.max(0, limit - 3)) + '...' : normalized
    }

    private static String lower(Object value) {
        return (value ?: '').toString().toLowerCase(Locale.ROOT)
    }

    private static String text(Object value) {
        return value == null ? '' : value.toString().trim()
    }
}

class ProjectKnowledgeContext {
    String projectKey
    List<ProjectKnowledgeContextEntry> entries = []
    int maxFacts
    int maxChars

    static ProjectKnowledgeContext empty() {
        return new ProjectKnowledgeContext(entries: [])
    }

    boolean hasEntries() {
        return entries != null && !entries.isEmpty()
    }

    String toPromptBlock() {
        if (!hasEntries()) {
            return ''
        }
        String items = entries.withIndex().collect { ProjectKnowledgeContextEntry entry, int index ->
            "${index + 1}. ${entry.key} [source=${entry.source}, confidence=${String.format(Locale.ROOT, '%.2f', entry.confidence)}, why=${entry.reason}]\n   ${entry.excerpt}".toString()
        }.join('\n')
        return """EvoForge selected project context for ${projectKey}:
${items}

Context policy: focused retrieval only; selected ${entries.size()} of at most ${maxFacts} facts within about ${maxChars} characters. Treat this as supporting context, not truth. Current user request and repository evidence outrank memory.
""".stripIndent().trim()
    }
}

class ProjectKnowledgeContextEntry {
    String key
    String source
    double confidence
    List<String> tags = []
    int score
    String reason
    String excerpt

    ProjectKnowledgeContextEntry copyWithExcerpt(String nextExcerpt) {
        return new ProjectKnowledgeContextEntry(
            key: key,
            source: source,
            confidence: confidence,
            tags: tags,
            score: score,
            reason: reason,
            excerpt: nextExcerpt
        )
    }
}

class ProjectKnowledgeContextPolicy {
    int maxFacts = 5
    int maxChars = 3200
    int maxCharsPerFact = 700

    static ProjectKnowledgeContextPolicy from(Object raw) {
        Map value = raw instanceof Map ? raw as Map : [:]
        return new ProjectKnowledgeContextPolicy(
            maxFacts: boundedInt(value.maxFacts, 5, 1, 10),
            maxChars: boundedInt(value.maxChars, 3200, 800, 8000),
            maxCharsPerFact: boundedInt(value.maxCharsPerFact, 700, 220, 1600)
        )
    }

    private static int boundedInt(Object raw, int fallback, int min, int max) {
        int value
        try {
            value = raw == null ? fallback : raw.toString().toInteger()
        } catch (Exception ignored) {
            value = fallback
        }
        return Math.max(min, Math.min(max, value))
    }
}

class ProjectKnowledgeIntent {
    boolean errorFocused
    boolean correctionFocused
    boolean skillFocused
    boolean architectureFocused
    boolean lineageFocused

    static ProjectKnowledgeIntent from(String task) {
        String text = (task ?: '').toLowerCase(Locale.ROOT)
        return new ProjectKnowledgeIntent(
            errorFocused: containsAny(text, ['异常', '报错', '错误', '失败', '修复', 'bug', 'error', 'failed', 'fix']),
            correctionFocused: containsAny(text, ['不是', '不对', '纠正', '修正', '更正', 'wrong', 'incorrect']),
            skillFocused: containsAny(text, ['技能', '工具', '能力', 'skill', 'tool', 'capability']),
            architectureFocused: containsAny(text, ['架构', '设计', '编排', '策略', '方案', '如何', '怎么', 'architecture', 'design']),
            lineageFocused: containsAny(text, ['变动', '脉络', '历史', '上次', '之前', '改过', 'change', 'history', 'lineage'])
        )
    }

    private static boolean containsAny(String text, List<String> needles) {
        return needles.any { text.contains(it) }
    }
}
