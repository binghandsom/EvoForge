package com.evoforge.agent

import org.springframework.stereotype.Service

import java.time.Instant

@Service
class AgentConversationMemoryService {
    private final AgentConversationStore store

    AgentConversationMemoryService(AgentConversationStore store) {
        this.store = store
    }

    String resolveThreadId(Map<String, Object> attributes = [:]) {
        Object explicit = attributes?.threadId ?: attributes?.conversationId ?: attributes?.sessionId
        if (explicit?.toString()?.trim()) {
            return explicit.toString().trim()
        }
        Object taskId = attributes?.taskId
        if (taskId?.toString()?.trim()) {
            return "task:${taskId}".toString()
        }
        Object userId = attributes?.userId
        Object deviceId = attributes?.deviceId
        if (userId || deviceId) {
            return "device:${deviceId ?: 'unknown'}:${userId ?: 'anonymous'}".toString()
        }
        return 'default'
    }

    AgentConversationThread ensureThread(String threadId,
                                         String title = '',
                                         Map<String, Object> metadata = [:]) {
        String normalizedThreadId = requireText(threadId, 'threadId')
        return store.findThread(normalizedThreadId).orElseGet {
            store.upsertThread(new AgentConversationThread(
                threadId: normalizedThreadId,
                title: title?.trim() ?: '新对话',
                metadata: metadata ?: [:],
                createdAt: Instant.now(),
                updatedAt: Instant.now()
            ))
        }
    }

    AgentConversationThread createThread(String threadId = '',
                                         String title = '',
                                         Map<String, Object> metadata = [:]) {
        String normalizedThreadId = threadId?.trim() ?: "thread-${UUID.randomUUID()}".toString()
        return store.upsertThread(new AgentConversationThread(
            threadId: normalizedThreadId,
            title: title?.trim() ?: '新对话',
            metadata: metadata ?: [:],
            createdAt: Instant.now(),
            updatedAt: Instant.now()
        ))
    }

    List<AgentConversationThread> listThreads(int limit = 50) {
        return store.listThreads(Math.max(1, Math.min(limit, 200)))
    }

    AgentConversationTurn append(String threadId,
                                 String role,
                                 String content,
                                 Map<String, Object> metadata = [:]) {
        String normalizedThreadId = requireText(threadId, 'threadId')
        String normalizedRole = requireText(role, 'role')
        String normalizedContent = content == null ? '' : content.toString()
        String titleCandidate = normalizedRole == 'user' ? titleFromContent(normalizedContent) : ''
        ensureThread(normalizedThreadId, titleCandidate, threadMetadata(metadata))
        AgentConversationTurn saved = store.append(new AgentConversationTurn(
            id: UUID.randomUUID().toString(),
            threadId: normalizedThreadId,
            role: normalizedRole,
            content: normalizedContent,
            metadata: metadata ?: [:],
            createdAt: Instant.now()
        ))
        store.touchThread(normalizedThreadId, normalizedRole, normalizedContent, saved.createdAt ?: Instant.now(), titleCandidate)
        return saved
    }

    List<AgentConversationTurn> recent(String threadId, int limit = 20) {
        String normalizedThreadId = requireText(threadId, 'threadId')
        int safeLimit = Math.max(1, Math.min(limit, 100))
        return store.listRecent(normalizedThreadId, safeLimit)
    }

    String formatForPrompt(List<AgentConversationTurn> turns) {
        if (!turns) {
            return '[]'
        }
        List<String> lines = []
        turns.eachWithIndex { turn, index ->
            String content = (turn.content ?: '').trim()
            if (content.length() > 1600) {
                content = content.substring(0, 1600) + '\n...[truncated]'
            }
            lines << "${index + 1}. ${turn.role ?: 'unknown'} @ ${turn.createdAt ?: ''}\n${content}".toString()
        }
        return lines.join('\n\n')
    }

    String searchableText(List<AgentConversationTurn> turns, String currentInput) {
        List<String> parts = []
        (turns ?: []).takeRight(8).each { turn ->
            if (turn.content) {
                parts << turn.content
            }
        }
        if (currentInput) {
            parts << currentInput
        }
        return parts.join('\n')
    }

    List<Map<String, Object>> toView(List<AgentConversationTurn> turns) {
        return (turns ?: []).collect { turn ->
            [
                id       : turn.id,
                threadId : turn.threadId,
                role     : turn.role,
                content  : turn.content,
                metadata : turn.metadata ?: [:],
                createdAt: turn.createdAt?.toString()
            ] as Map<String, Object>
        }
    }

    List<Map<String, Object>> threadViews(List<AgentConversationThread> threads) {
        return (threads ?: []).collect { thread -> threadView(thread) }
    }

    Map<String, Object> threadView(AgentConversationThread thread) {
        return [
            threadId   : thread.threadId,
            title      : thread.title ?: '新对话',
            summary    : thread.summary ?: '',
            metadata   : thread.metadata ?: [:],
            turnCount  : thread.turnCount,
            lastRole   : thread.lastRole ?: '',
            lastContent: thread.lastContent ?: '',
            createdAt  : thread.createdAt?.toString(),
            updatedAt  : thread.updatedAt?.toString()
        ] as Map<String, Object>
    }

    private static Map<String, Object> threadMetadata(Map<String, Object> metadata) {
        return (metadata ?: [:]).findAll { key, value ->
            value != null && ['taskId', 'userId', 'deviceId', 'source'].contains(key?.toString())
        } as Map<String, Object>
    }

    private static String titleFromContent(String content) {
        String normalized = (content ?: '')
            .replaceAll('\\s+', ' ')
            .trim()
        if (!normalized) {
            return '新对话'
        }
        return normalized.length() > 42 ? normalized.substring(0, 42) + '...' : normalized
    }

    private static String requireText(Object value, String field) {
        String text = value == null ? '' : value.toString().trim()
        if (!text) {
            throw new IllegalArgumentException("${field} is required")
        }
        return text
    }
}
