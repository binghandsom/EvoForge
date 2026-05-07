package com.evoforge.agent

import com.evoforge.core.EvoForgeProperties
import com.fasterxml.jackson.core.type.TypeReference
import com.fasterxml.jackson.databind.ObjectMapper
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.stereotype.Component

import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant
import java.util.concurrent.locks.ReentrantReadWriteLock

@Component
@ConditionalOnProperty(prefix = 'evoforge.skills', name = 'storageBackend', havingValue = 'file')
class FileAgentConversationStore implements AgentConversationStore {
    private final ObjectMapper objectMapper
    private final Path storagePath
    private final ReentrantReadWriteLock lock = new ReentrantReadWriteLock()

    FileAgentConversationStore(ObjectMapper objectMapper, EvoForgeProperties properties) {
        this.objectMapper = objectMapper
        this.storagePath = Path.of(properties.agent.conversationStorage)
        ensureStorageExists()
    }

    @Override
    AgentConversationThread upsertThread(AgentConversationThread thread) {
        lock.writeLock().lock()
        try {
            Map<String, List> data = loadDataUnsafe()
            List<AgentConversationThread> threads = data.threads as List<AgentConversationThread>
            AgentConversationThread existing = threads.find { it.threadId == thread.threadId }
            if (existing) {
                existing.title = thread.title ?: existing.title
                existing.summary = thread.summary ?: existing.summary
                existing.metadata = new LinkedHashMap<>((existing.metadata ?: [:]) + (thread.metadata ?: [:]))
                existing.updatedAt = thread.updatedAt ?: Instant.now()
                writeDataUnsafe(data)
                return existing
            }
            thread.threadId = thread.threadId ?: UUID.randomUUID().toString()
            thread.createdAt = thread.createdAt ?: Instant.now()
            thread.updatedAt = thread.updatedAt ?: Instant.now()
            threads << thread
            writeDataUnsafe(data)
            return thread
        } finally {
            lock.writeLock().unlock()
        }
    }

    @Override
    Optional<AgentConversationThread> findThread(String threadId) {
        lock.readLock().lock()
        try {
            return Optional.ofNullable((loadDataUnsafe().threads as List<AgentConversationThread>).find { it.threadId == threadId })
        } finally {
            lock.readLock().unlock()
        }
    }

    @Override
    List<AgentConversationThread> listThreads(int limit) {
        lock.readLock().lock()
        try {
            return (loadDataUnsafe().threads as List<AgentConversationThread>)
                .sort { a, b -> b.updatedAt <=> a.updatedAt }
                .take(Math.max(1, Math.min(limit, 200)))
        } finally {
            lock.readLock().unlock()
        }
    }

    @Override
    void touchThread(String threadId, String role, String content, Instant updatedAt, String titleCandidate) {
        lock.writeLock().lock()
        try {
            Map<String, List> data = loadDataUnsafe()
            List<AgentConversationThread> threads = data.threads as List<AgentConversationThread>
            AgentConversationThread thread = threads.find { it.threadId == threadId }
            if (!thread) {
                thread = new AgentConversationThread(threadId: threadId, title: titleCandidate ?: '新对话')
                threads << thread
            }
            if ((!thread.title || thread.title == '新对话') && titleCandidate) {
                thread.title = titleCandidate
            }
            thread.turnCount = thread.turnCount + 1
            thread.lastRole = role ?: ''
            thread.lastContent = compact(content ?: '', 400)
            thread.updatedAt = updatedAt ?: Instant.now()
            writeDataUnsafe(data)
        } finally {
            lock.writeLock().unlock()
        }
    }

    @Override
    AgentConversationTurn append(AgentConversationTurn turn) {
        lock.writeLock().lock()
        try {
            Map<String, List> data = loadDataUnsafe()
            List<AgentConversationTurn> all = data.turns as List<AgentConversationTurn>
            turn.id = turn.id ?: UUID.randomUUID().toString()
            all << turn
            writeDataUnsafe(data)
            return turn
        } finally {
            lock.writeLock().unlock()
        }
    }

    @Override
    List<AgentConversationTurn> listRecent(String threadId, int limit) {
        lock.readLock().lock()
        try {
            return (loadDataUnsafe().turns as List<AgentConversationTurn>)
                .findAll { it.threadId == threadId }
                .sort { a, b -> a.createdAt <=> b.createdAt }
                .takeRight(Math.max(1, Math.min(limit, 100)))
        } finally {
            lock.readLock().unlock()
        }
    }

    private Map<String, List> loadDataUnsafe() {
        if (!Files.exists(storagePath) || Files.size(storagePath) == 0) {
            return [threads: [], turns: []]
        }
        Object raw = objectMapper.readValue(storagePath.toFile(), Object)
        if (raw instanceof List) {
            List<AgentConversationTurn> turns = ((List) raw).collect { objectMapper.convertValue(it, AgentConversationTurn) }
            return [threads: deriveThreads(turns), turns: turns]
        }
        Map map = raw instanceof Map ? (Map) raw : [:]
        List<AgentConversationThread> threads = (map.threads instanceof List ? map.threads : [])
            .collect { objectMapper.convertValue(it, AgentConversationThread) }
        List<AgentConversationTurn> turns = (map.turns instanceof List ? map.turns : [])
            .collect { objectMapper.convertValue(it, AgentConversationTurn) }
        return [threads: threads, turns: turns]
    }

    private void writeDataUnsafe(Map<String, List> data) {
        objectMapper.writerWithDefaultPrettyPrinter().writeValue(storagePath.toFile(), [
            threads: data.threads ?: [],
            turns  : data.turns ?: []
        ])
    }

    private static List<AgentConversationThread> deriveThreads(List<AgentConversationTurn> turns) {
        Map<String, AgentConversationThread> threads = [:]
        (turns ?: []).sort { a, b -> a.createdAt <=> b.createdAt }.each { turn ->
            AgentConversationThread thread = threads[turn.threadId]
            if (!thread) {
                thread = new AgentConversationThread(
                    threadId: turn.threadId,
                    title: turn.role == 'user' ? compact(turn.content ?: '', 42) : '新对话',
                    createdAt: turn.createdAt ?: Instant.now()
                )
                threads[turn.threadId] = thread
            }
            thread.turnCount = thread.turnCount + 1
            thread.lastRole = turn.role ?: ''
            thread.lastContent = compact(turn.content ?: '', 400)
            thread.updatedAt = turn.createdAt ?: Instant.now()
        }
        return threads.values().toList()
    }

    private void ensureStorageExists() {
        if (storagePath.parent && !Files.exists(storagePath.parent)) {
            Files.createDirectories(storagePath.parent)
        }
        if (!Files.exists(storagePath)) {
            Files.createFile(storagePath)
            storagePath.toFile().text = '{"threads":[],"turns":[]}'
        }
    }

    private static String compact(String text, int limit) {
        String normalized = (text ?: '').replaceAll('\\s+', ' ').trim()
        return normalized.length() > limit ? normalized.substring(0, limit) + '...' : normalized
    }
}
