package com.evoforge.agent

import com.evoforge.device.CodexTaskResult
import com.evoforge.device.DeviceCommandMessage
import com.evoforge.device.DeviceProtocol
import com.fasterxml.jackson.databind.ObjectMapper
import org.springframework.stereotype.Service

import java.time.Instant
import java.util.Locale

@Service
class ProjectLearningService {
    private final AgentKnowledgeService knowledgeService
    private final ObjectMapper objectMapper

    ProjectLearningService(AgentKnowledgeService knowledgeService,
                           ObjectMapper objectMapper) {
        this.knowledgeService = knowledgeService
        this.objectMapper = objectMapper
    }

    Map<String, Object> recordCodexTask(DeviceCommandMessage command, CodexTaskResult result) {
        Map learning = learningConfig(command)
        if (learning.enabled != true) {
            return [enabled: false] as Map<String, Object>
        }

        String sourceProjectKey = text(learning.sourceProjectKey) ?: projectKey(command) ?: 'unknown-project'
        String targetProjectKey = text(learning.targetProjectKey) ?: 'evoforge'
        Instant now = Instant.now()
        String taskId = text(command.taskId) ?: UUID.randomUUID().toString()
        boolean failed = result?.status == DeviceProtocol.STATUS_FAILED
        boolean correction = correctionCandidate(command?.text, result?.output ?: result?.message)
        String scope = "project:${sourceProjectKey}".toString()
        String latestKey = "project.${sourceProjectKey}.latest_change".toString()
        Map<String, Object> previousChange = previousChangeSummary(knowledgeService.findByKey(latestKey, scope).orElse(null))
        List<String> learningScopes = learningScopes(learning)
        String episodeKey = "project.${sourceProjectKey}.change.${safeInstant(now)}.${shortId(taskId)}".toString()

        Map<String, Object> episode = [
            kind            : 'codex-task-change-episode',
            taskId          : taskId,
            threadId        : text(command?.attributes?.threadId),
            sourceProjectKey: sourceProjectKey,
            targetProjectKey: targetProjectKey,
            commandType     : command?.type,
            userTask        : compact(command?.text, 4000),
            learningScopes  : learningScopes,
            status          : result?.status ?: 'unknown',
            message         : compact(result?.message, 1000),
            codexOutput     : compact(result?.output, 6000),
            failed          : failed,
            correctionHint  : correction,
            previousChange  : previousChange,
            recordedAt      : now.toString()
        ].findAll { it.value != null } as Map<String, Object>

        List<String> tags = [
            'project',
            sourceProjectKey,
            'task-intent',
            'change-lineage',
            'codex-output',
            failed ? 'error' : 'success',
            correction ? 'correction-candidate' : null
        ].findAll { it } as List<String>

        AgentKnowledgeFact episodeFact = knowledgeService.upsert(
            episodeKey,
            objectMapper.writeValueAsString(episode),
            scope,
            tags,
            'codex-learning',
            failed ? 0.72d : 0.82d
        )

        AgentKnowledgeFact latestFact = knowledgeService.upsert(
            latestKey,
            objectMapper.writeValueAsString([
                taskId          : taskId,
                threadId        : text(command?.attributes?.threadId),
                sourceProjectKey: sourceProjectKey,
                targetProjectKey: targetProjectKey,
                userTask        : compact(command?.text, 1200),
                learningScopes  : learningScopes,
                status          : result?.status ?: 'unknown',
                message         : compact(result?.message, 600),
                codexOutput     : compact(result?.output, 1800),
                previousChange  : previousChange,
                recordedAt      : now.toString()
            ].findAll { it.value != null }),
            scope,
            ['project', sourceProjectKey, 'task-intent', 'latest-change', failed ? 'error' : 'success'],
            'codex-learning',
            failed ? 0.70d : 0.80d
        )

        AgentKnowledgeFact correctionFact = null
        if (correction) {
            correctionFact = knowledgeService.upsert(
                "project.${sourceProjectKey}.correction.${shortId(taskId)}".toString(),
                objectMapper.writeValueAsString([
                    taskId          : taskId,
                    sourceProjectKey: sourceProjectKey,
                    userTask        : compact(command?.text, 1800),
                    evidence        : compact(result?.output ?: result?.message, 1800),
                    recordedAt      : now.toString()
                ]),
                scope,
                ['project', sourceProjectKey, 'correction-candidate', 'needs-review'],
                'codex-learning',
                0.68d
            )
        }

        return [
            enabled         : true,
            sourceProjectKey: sourceProjectKey,
            targetProjectKey: targetProjectKey,
            episodeKey      : episodeFact.key,
            latestKey       : latestFact.key,
            correctionKey   : correctionFact?.key,
            status          : result?.status ?: 'unknown'
        ].findAll { it.value != null } as Map<String, Object>
    }

    private static Map learningConfig(DeviceCommandMessage command) {
        Object raw = command?.attributes?.evoforgeLearning
        if (raw instanceof Map) {
            Map config = new LinkedHashMap(raw as Map)
            config.enabled = config.enabled == true
            return config
        }
        return [enabled: raw == true || command?.attributes?.learnWithEvoForge == true]
    }

    private Map<String, Object> previousChangeSummary(AgentKnowledgeFact fact) {
        if (!fact) {
            return null
        }
        try {
            Map value = objectMapper.readValue(fact.value ?: '{}', Map)
            return [
                knowledgeKey: fact.key,
                taskId      : text(value.taskId),
                userTask    : compact(value.userTask, 600),
                status      : text(value.status),
                recordedAt  : text(value.recordedAt),
                updatedAt   : fact.updatedAt?.toString()
            ].findAll { it.value != null && it.value != '' } as Map<String, Object>
        } catch (Exception ignored) {
            return [
                knowledgeKey: fact.key,
                value       : compact(fact.value, 900),
                updatedAt   : fact.updatedAt?.toString()
            ].findAll { it.value != null } as Map<String, Object>
        }
    }

    private static List<String> learningScopes(Map learning) {
        Object raw = learning.learningScopes
        if (!(raw instanceof Collection)) {
            return [
                'task-intent',
                'change-lineage',
                'codex-output',
                'errors',
                'skills',
                'project-facts'
            ]
        }
        return raw.collect { text(it) }.findAll { it }.unique()
    }

    private static String projectKey(DeviceCommandMessage command) {
        Object value = command?.attributes?.projectKey ?: command?.attributes?.workspaceKey ?: command?.attributes?.project
        return text(value)
    }

    private static boolean correctionCandidate(Object task, Object output) {
        String text = "${task ?: ''}\n${output ?: ''}".toLowerCase(Locale.ROOT)
        return ['不是', '不对', '错误', '修正', '纠正', 'wrong', 'incorrect', 'fix'].any { text.contains(it) }
    }

    private static String compact(Object value, int limit) {
        String normalized = (value ?: '').toString().replaceAll('\\s+', ' ').trim()
        return normalized.length() > limit ? normalized.take(limit) + '...' : normalized
    }

    private static String text(Object value) {
        String result = value == null ? '' : value.toString().trim()
        return result
    }

    private static String shortId(String value) {
        String text = value ?: UUID.randomUUID().toString()
        return text.length() <= 8 ? text : text.substring(text.length() - 8)
    }

    private static String safeInstant(Instant instant) {
        return instant.toString().replaceAll('[^0-9A-Za-z]+', '')
    }
}
