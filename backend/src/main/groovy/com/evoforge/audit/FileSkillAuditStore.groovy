package com.evoforge.audit

import com.evoforge.core.EvoForgeProperties
import com.fasterxml.jackson.core.type.TypeReference
import com.fasterxml.jackson.databind.ObjectMapper
import org.springframework.stereotype.Component

import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.locks.ReentrantReadWriteLock

@Component
class FileSkillAuditStore implements SkillAuditStore {
    private final ObjectMapper objectMapper
    private final Path storagePath
    private final ReentrantReadWriteLock lock = new ReentrantReadWriteLock()

    FileSkillAuditStore(ObjectMapper objectMapper, EvoForgeProperties properties) {
        this.objectMapper = objectMapper
        this.storagePath = Path.of(properties.skills.auditStorage)
        ensureStorageExists()
    }

    @Override
    List<SkillEvent> loadAll() {
        lock.readLock().lock()
        try {
            if (!Files.exists(storagePath) || Files.size(storagePath) == 0) {
                return []
            }
            return objectMapper.readValue(storagePath.toFile(), new TypeReference<List<SkillEvent>>() {})
        } finally {
            lock.readLock().unlock()
        }
    }

    @Override
    void append(SkillEvent event) {
        lock.writeLock().lock()
        try {
            def all = loadAll()
            all << event
            writeAll(all)
        } finally {
            lock.writeLock().unlock()
        }
    }

    @Override
    List<SkillEvent> listForSkill(String skillId) {
        return loadAll().findAll { it.skillId == skillId }
    }

    @Override
    void clear() {
        lock.writeLock().lock()
        try {
            writeAll([])
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

    private void writeAll(List<SkillEvent> all) {
        objectMapper.writerWithDefaultPrettyPrinter().writeValue(storagePath.toFile(), all)
    }
}
