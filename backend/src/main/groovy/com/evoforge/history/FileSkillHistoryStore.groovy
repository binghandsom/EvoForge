package com.evoforge.history

import com.evoforge.core.EvoForgeProperties
import com.fasterxml.jackson.core.type.TypeReference
import com.fasterxml.jackson.databind.ObjectMapper
import org.springframework.stereotype.Component

import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.locks.ReentrantReadWriteLock

@Component
class FileSkillHistoryStore implements SkillHistoryStore {
    private final ObjectMapper objectMapper
    private final Path storagePath
    private final ReentrantReadWriteLock lock = new ReentrantReadWriteLock()

    FileSkillHistoryStore(ObjectMapper objectMapper, EvoForgeProperties properties) {
        this.objectMapper = objectMapper
        this.storagePath = Path.of(properties.skills.historyStorage)
        ensureStorageExists()
    }

    @Override
    List<SkillHistoryEntry> loadAll() {
        lock.readLock().lock()
        try {
            if (!Files.exists(storagePath) || Files.size(storagePath) == 0) {
                return []
            }
            return objectMapper.readValue(storagePath.toFile(), new TypeReference<List<SkillHistoryEntry>>() {})
        } finally {
            lock.readLock().unlock()
        }
    }

    @Override
    void append(SkillHistoryEntry entry) {
        lock.writeLock().lock()
        try {
            def all = loadAll()
            all << entry
            writeAll(all)
        } finally {
            lock.writeLock().unlock()
        }
    }

    @Override
    List<SkillHistoryEntry> listForSkill(String skillId) {
        return loadAll().findAll { it.skillId == skillId }
    }

    @Override
    SkillHistoryEntry findById(String id) {
        return loadAll().find { it.id == id }
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

    private void writeAll(List<SkillHistoryEntry> all) {
        objectMapper.writerWithDefaultPrettyPrinter().writeValue(storagePath.toFile(), all)
    }
}
