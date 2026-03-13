package com.evoforge.store

import com.evoforge.core.EvoForgeProperties
import com.evoforge.model.SkillDefinition
import com.fasterxml.jackson.core.type.TypeReference
import com.fasterxml.jackson.databind.ObjectMapper
import org.springframework.stereotype.Component

import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.locks.ReentrantReadWriteLock

@Component
class FileSkillStore implements SkillStore {
    private final ObjectMapper objectMapper
    private final Path storagePath
    private final ReentrantReadWriteLock lock = new ReentrantReadWriteLock()

    FileSkillStore(ObjectMapper objectMapper, EvoForgeProperties properties) {
        this.objectMapper = objectMapper
        this.storagePath = Path.of(properties.skills.storage)
        ensureStorageExists()
    }

    @Override
    List<SkillDefinition> loadAll() {
        lock.readLock().lock()
        try {
            if (!Files.exists(storagePath) || Files.size(storagePath) == 0) {
                return []
            }
            return objectMapper.readValue(storagePath.toFile(), new TypeReference<List<SkillDefinition>>() {})
        } finally {
            lock.readLock().unlock()
        }
    }

    @Override
    Optional<SkillDefinition> findById(String id) {
        def all = loadAll()
        return Optional.ofNullable(all.find { it.id == id })
    }

    @Override
    SkillDefinition save(SkillDefinition skill) {
        lock.writeLock().lock()
        try {
            def all = loadAll()
            def existingIndex = all.findIndexOf { it.id == skill.id }
            if (existingIndex >= 0) {
                all[existingIndex] = skill
            } else {
                all << skill
            }
            writeAll(all)
            return skill
        } finally {
            lock.writeLock().unlock()
        }
    }

    @Override
    void delete(String id) {
        lock.writeLock().lock()
        try {
            def all = loadAll().findAll { it.id != id }
            writeAll(all)
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

    private void writeAll(List<SkillDefinition> all) {
        objectMapper.writerWithDefaultPrettyPrinter().writeValue(storagePath.toFile(), all)
    }
}
