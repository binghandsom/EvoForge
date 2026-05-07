package com.evoforge.llm

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
class FileModelProviderConfigStore implements ModelProviderConfigStore {
    private final ObjectMapper objectMapper
    private final Path storagePath
    private final ReentrantReadWriteLock lock = new ReentrantReadWriteLock()

    FileModelProviderConfigStore(ObjectMapper objectMapper, EvoForgeProperties properties) {
        this.objectMapper = objectMapper
        this.storagePath = Path.of(properties.models.configStorage)
        ensureStorageExists()
    }

    @Override
    List<ModelProviderConfig> loadAll() {
        lock.readLock().lock()
        try {
            if (!Files.exists(storagePath) || Files.size(storagePath) == 0) {
                return []
            }
            return objectMapper.readValue(storagePath.toFile(), new TypeReference<List<ModelProviderConfig>>() {})
        } finally {
            lock.readLock().unlock()
        }
    }

    @Override
    Optional<ModelProviderConfig> findById(String id) {
        return Optional.ofNullable(loadAll().find { it.id == id })
    }

    @Override
    ModelProviderConfig save(ModelProviderConfig config) {
        lock.writeLock().lock()
        try {
            def all = loadAll()
            def existingIndex = all.findIndexOf { it.id == config.id }
            if (existingIndex >= 0) {
                all[existingIndex] = config
            } else {
                all << config
            }
            writeAll(all)
            return config
        } finally {
            lock.writeLock().unlock()
        }
    }

    @Override
    void delete(String id) {
        lock.writeLock().lock()
        try {
            writeAll(loadAll().findAll { it.id != id })
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

    private void writeAll(List<ModelProviderConfig> all) {
        objectMapper.writerWithDefaultPrettyPrinter().writeValue(storagePath.toFile(), all)
    }
}
