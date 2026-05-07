package com.evoforge.agent

import com.evoforge.core.EvoForgeProperties
import com.fasterxml.jackson.core.type.TypeReference
import com.fasterxml.jackson.databind.ObjectMapper
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.stereotype.Component

import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.locks.ReentrantReadWriteLock

@Component
@ConditionalOnProperty(prefix = 'evoforge.skills', name = 'storageBackend', havingValue = 'file')
class FileAgentKnowledgeStore implements AgentKnowledgeStore {
    private final ObjectMapper objectMapper
    private final Path storagePath
    private final ReentrantReadWriteLock lock = new ReentrantReadWriteLock()

    FileAgentKnowledgeStore(ObjectMapper objectMapper, EvoForgeProperties properties) {
        this.objectMapper = objectMapper
        this.storagePath = Path.of(properties.agent.knowledgeStorage)
        ensureStorageExists()
    }

    @Override
    List<AgentKnowledgeFact> loadAll() {
        lock.readLock().lock()
        try {
            if (!Files.exists(storagePath) || Files.size(storagePath) == 0) {
                return []
            }
            return objectMapper.readValue(storagePath.toFile(), new TypeReference<List<AgentKnowledgeFact>>() {})
        } finally {
            lock.readLock().unlock()
        }
    }

    @Override
    Optional<AgentKnowledgeFact> findByKey(String key, String scope) {
        return Optional.ofNullable(loadAll().find { it.key == key && (it.scope ?: 'global') == (scope ?: 'global') })
    }

    @Override
    AgentKnowledgeFact save(AgentKnowledgeFact fact) {
        lock.writeLock().lock()
        try {
            def all = loadAll()
            int index = all.findIndexOf { it.id == fact.id }
            if (index >= 0) {
                all[index] = fact
            } else {
                all << fact
            }
            objectMapper.writerWithDefaultPrettyPrinter().writeValue(storagePath.toFile(), all)
            return fact
        } finally {
            lock.writeLock().unlock()
        }
    }

    @Override
    void delete(String id) {
        lock.writeLock().lock()
        try {
            objectMapper.writerWithDefaultPrettyPrinter().writeValue(storagePath.toFile(), loadAll().findAll { it.id != id })
        } finally {
            lock.writeLock().unlock()
        }
    }

    private void ensureStorageExists() {
        if (storagePath.parent && !Files.exists(storagePath.parent)) {
            Files.createDirectories(storagePath.parent)
        }
        if (!Files.exists(storagePath)) {
            Files.createFile(storagePath)
            storagePath.toFile().text = '[]'
        }
    }
}
