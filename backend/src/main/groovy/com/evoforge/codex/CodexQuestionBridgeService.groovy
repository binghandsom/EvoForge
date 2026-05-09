package com.evoforge.codex

import com.evoforge.core.EvoForgeProperties
import com.evoforge.device.DeviceCommandMessage
import com.evoforge.device.DeviceEventPublisher
import com.evoforge.device.DeviceProtocol
import com.evoforge.device.DeviceTaskEvent
import org.springframework.stereotype.Service

import java.time.Instant
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

@Service
class CodexQuestionBridgeService {
    private final DeviceEventPublisher eventPublisher
    private final EvoForgeProperties properties
    private final Map<String, PendingCodexQuestion> pending = new ConcurrentHashMap<>()

    CodexQuestionBridgeService(DeviceEventPublisher eventPublisher,
                               EvoForgeProperties properties) {
        this.eventPublisher = eventPublisher
        this.properties = properties
    }

    Map<String, Object> capability(String baseUrl) {
        String safeBaseUrl = normalizeBaseUrl(baseUrl)
        return [
            endpoint      : "${safeBaseUrl}/api/codex/bridge/questions/ask".toString(),
            responseType  : DeviceProtocol.TYPE_HUMAN_RESPONSE,
            eventPayload  : 'codexQuestion',
            answerPayload : 'codexQuestionAnswer',
            timeoutSeconds: properties.codexTask.questionTimeoutSeconds,
            suggestedCall : """curl -s -X POST '${safeBaseUrl}/api/codex/bridge/questions/ask' -H 'Content-Type: application/json' -d '{"taskId":"<current task id>","projectKey":"<projectKey>","question":"<question for user>","options":[]}'"""
        ] as Map<String, Object>
    }

    Map<String, Object> ask(Map request) {
        int timeoutSeconds = safeInt(request?.timeoutSeconds, properties.codexTask.questionTimeoutSeconds, 1, 7200)
        String questionId = text(request?.questionId) ?: UUID.randomUUID().toString()
        String taskId = text(request?.taskId) ?: "codex-question-${questionId}".toString()
        String question = required(request?.question ?: request?.input, 'question')
        PendingCodexQuestion pendingQuestion = new PendingCodexQuestion(
            questionId: questionId,
            taskId: taskId,
            userId: text(request?.userId) ?: properties.deviceAgent.userId,
            deviceId: text(request?.deviceId) ?: properties.deviceAgent.deviceId,
            projectKey: text(request?.projectKey),
            question: question,
            options: listValue(request?.options),
            allowFreeText: request?.allowFreeText != false,
            requester: text(request?.requester) ?: 'codex',
            skillProposal: request?.skillProposal instanceof Map ? request.skillProposal as Map<String, Object> : [:],
            createdAt: Instant.now(),
            timeoutSeconds: timeoutSeconds
        )
        pending[questionId] = pendingQuestion
        publishQuestion(pendingQuestion, request)

        try {
            Map<String, Object> answer = pendingQuestion.future.get(timeoutSeconds, TimeUnit.SECONDS)
            return [
                questionId: questionId,
                taskId    : taskId,
                answered  : true,
                timedOut  : false,
                answer    : answer.answer,
                actor     : answer.actor,
                note      : answer.note,
                answeredAt: answer.answeredAt
            ].findAll { it.value != null } as Map<String, Object>
        } catch (TimeoutException ignored) {
            pending.remove(questionId)
            publishTimeout(pendingQuestion)
            return [
                questionId: questionId,
                taskId    : taskId,
                answered  : false,
                timedOut  : true,
                error     : "No mobile response received within ${timeoutSeconds}s".toString()
            ] as Map<String, Object>
        } finally {
            pending.remove(questionId)
        }
    }

    Map<String, Object> answer(DeviceCommandMessage command) {
        Map payload = responsePayload(command)
        String questionId = required(payload.questionId ?: command?.attributes?.questionId, 'questionId')
        String answer = required(payload.answer ?: command?.text, 'answer')
        String actor = text(payload.actor ?: command?.attributes?.actor) ?: 'mobile'
        Map<String, Object> response = [
            questionId: questionId,
            taskId    : text(payload.taskId ?: payload.questionTaskId ?: command?.taskId),
            answer    : answer,
            actor     : actor,
            note      : text(payload.note ?: command?.attributes?.note),
            answeredAt: Instant.now().toString()
        ].findAll { it.value != null && it.value != '' } as Map<String, Object>

        PendingCodexQuestion pendingQuestion = pending.remove(questionId)
        if (pendingQuestion) {
            response.taskId = response.taskId ?: pendingQuestion.taskId
            pendingQuestion.future.complete(response)
        }
        publishAnswer(response, pendingQuestion)
        return [
            ok        : pendingQuestion != null,
            questionId: questionId,
            taskId    : response.taskId,
            answered  : true,
            delivered : pendingQuestion != null
        ] as Map<String, Object>
    }

    private void publishQuestion(PendingCodexQuestion question, Map request) {
        eventPublisher.publish(new DeviceTaskEvent(
            taskId: question.taskId,
            userId: question.userId,
            deviceId: question.deviceId,
            type: DeviceProtocol.STATUS_NEEDS_INPUT,
            status: DeviceProtocol.STATUS_NEEDS_INPUT,
            level: 'warn',
            message: "${requesterLabel(question.requester)} asks: ${compact(question.question, 160)}".toString(),
            recoverable: true,
            payload: [
                commandType  : 'codex_question',
                projectKey   : question.projectKey,
                threadId     : text(request?.threadId),
                codexQuestion: question.toMap()
            ].findAll { it.value != null && it.value != '' } as Map<String, Object>
        ))
    }

    private void publishAnswer(Map<String, Object> response, PendingCodexQuestion pendingQuestion) {
        eventPublisher.publish(new DeviceTaskEvent(
            taskId: text(response.taskId) ?: pendingQuestion?.taskId ?: "codex-question-${response.questionId}".toString(),
            userId: pendingQuestion?.userId ?: properties.deviceAgent.userId,
            deviceId: pendingQuestion?.deviceId ?: properties.deviceAgent.deviceId,
            type: DeviceProtocol.STATUS_INPUT_RECEIVED,
            status: DeviceProtocol.STATUS_INPUT_RECEIVED,
            level: 'info',
            message: pendingQuestion?.requester == 'agent-skill-proposal'
                ? 'Mobile response delivered to EvoForge skill proposal'
                : 'Mobile response delivered to Codex',
            recoverable: false,
            payload: [
                commandType        : DeviceProtocol.TYPE_HUMAN_RESPONSE,
                codexQuestionAnswer: response
            ] as Map<String, Object>
        ))
    }

    private void publishTimeout(PendingCodexQuestion question) {
        eventPublisher.publish(new DeviceTaskEvent(
            taskId: question.taskId,
            userId: question.userId,
            deviceId: question.deviceId,
            type: DeviceProtocol.STATUS_FAILED,
            status: DeviceProtocol.STATUS_FAILED,
            level: 'error',
            message: "Codex question timed out after ${question.timeoutSeconds}s",
            recoverable: true,
            payload: [
                commandType  : 'codex_question',
                codexQuestion: question.toMap(false),
                timedOut     : true
            ] as Map<String, Object>
        ))
    }

    private static Map responsePayload(DeviceCommandMessage command) {
        Object raw = command?.attributes?.codexQuestionAnswer ?: command?.attributes?.humanResponse ?: command?.attributes?.response
        return raw instanceof Map ? raw as Map : command?.attributes ?: [:]
    }

    private static String requesterLabel(String requester) {
        switch (text(requester)) {
            case 'agent-skill-proposal':
                return 'EvoForge skill proposal'
            case 'agent':
                return 'EvoForge'
            case 'codex':
            default:
                return 'Codex'
        }
    }

    private static List<String> listValue(Object raw) {
        if (raw instanceof Collection) {
            return raw.collect { text(it) }.findAll { it }.unique()
        }
        String value = text(raw)
        return value ? [value] : []
    }

    private static String required(Object value, String field) {
        String text = text(value)
        if (!text) {
            throw new IllegalArgumentException("${field} is required")
        }
        return text
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

    private static String compact(Object value, int limit) {
        String normalized = text(value).replaceAll('\\s+', ' ')
        return normalized.length() > limit ? normalized.take(limit) + '...' : normalized
    }

    private static String normalizeBaseUrl(String baseUrl) {
        String value = text(baseUrl) ?: 'http://localhost:18080'
        return value.endsWith('/') ? value.substring(0, value.length() - 1) : value
    }

    private static String text(Object value) {
        return value == null ? '' : value.toString().trim()
    }
}

class PendingCodexQuestion {
    String questionId
    String taskId
    String userId
    String deviceId
    String projectKey
    String question
    List<String> options = []
    boolean allowFreeText = true
    String requester
    Map<String, Object> skillProposal = [:]
    Instant createdAt
    int timeoutSeconds
    CompletableFuture<Map<String, Object>> future = new CompletableFuture<>()

    Map<String, Object> toMap(boolean includeQuestion = true) {
        return [
            questionId    : questionId,
            taskId        : taskId,
            projectKey    : projectKey,
            question      : includeQuestion ? question : null,
            options       : options,
            allowFreeText : allowFreeText,
            requester     : requester,
            skillProposal : skillProposal,
            timeoutSeconds: timeoutSeconds,
            createdAt     : createdAt?.toString()
        ].findAll { it.value != null && it.value != '' && (!(it.value instanceof Collection) || !it.value.isEmpty()) } as Map<String, Object>
    }
}
