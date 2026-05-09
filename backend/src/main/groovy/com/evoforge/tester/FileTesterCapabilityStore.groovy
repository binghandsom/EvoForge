package com.evoforge.tester

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
class FileTesterCapabilityStore implements TesterCapabilityStore {
    private final ObjectMapper objectMapper
    private final Path storagePath
    private final ReentrantReadWriteLock lock = new ReentrantReadWriteLock()

    FileTesterCapabilityStore(ObjectMapper objectMapper, EvoForgeProperties properties) {
        this.objectMapper = objectMapper
        this.storagePath = Path.of(properties.tester.capabilityStorage)
        ensureStorageExists()
    }

    @Override
    List<TesterCapability> loadAll() {
        lock.readLock().lock()
        try {
            if (!Files.exists(storagePath) || Files.size(storagePath) == 0) {
                return []
            }
            return objectMapper.readValue(storagePath.toFile(), new TypeReference<List<TesterCapability>>() {})
        } finally {
            lock.readLock().unlock()
        }
    }

    @Override
    List<TesterCapability> findByProject(String projectKey) {
        return loadAll().findAll { it.projectKey == projectKey }.sort { a, b -> a.id <=> b.id }
    }

    @Override
    Optional<TesterCapability> findByProjectAndId(String projectKey, String id) {
        return Optional.ofNullable(loadAll().find { it.projectKey == projectKey && it.id == id })
    }

    @Override
    TesterCapability save(TesterCapability capability) {
        lock.writeLock().lock()
        try {
            capability.recordId = capability.recordId ?: UUID.randomUUID().toString()
            capability.createdAt = capability.createdAt ?: Instant.now()
            capability.updatedAt = Instant.now()
            List<TesterCapability> all = loadAll()
            int existingIndex = all.findIndexOf { it.projectKey == capability.projectKey && it.id == capability.id }
            if (existingIndex >= 0) {
                capability.recordId = all[existingIndex].recordId ?: capability.recordId
                capability.createdAt = all[existingIndex].createdAt ?: capability.createdAt
                all[existingIndex] = capability
            } else {
                all << capability
            }
            writeAll(all)
            return capability
        } finally {
            lock.writeLock().unlock()
        }
    }

    @Override
    void delete(String projectKey, String id) {
        lock.writeLock().lock()
        try {
            writeAll(loadAll().findAll { !(it.projectKey == projectKey && it.id == id) })
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

    private void writeAll(List<TesterCapability> all) {
        objectMapper.writerWithDefaultPrettyPrinter().writeValue(storagePath.toFile(), all)
    }
}

