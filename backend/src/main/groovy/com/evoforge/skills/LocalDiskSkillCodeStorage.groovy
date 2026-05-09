package com.evoforge.skills

import com.evoforge.core.EvoForgeProperties
import com.evoforge.model.SkillDefinition
import com.fasterxml.jackson.databind.ObjectMapper
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component

import java.nio.file.Files
import java.nio.file.Path

@Component
class LocalDiskSkillCodeStorage implements SkillCodeStorage {
    private static final Logger log = LoggerFactory.getLogger(LocalDiskSkillCodeStorage)

    private final EvoForgeProperties properties
    private final ObjectMapper objectMapper

    LocalDiskSkillCodeStorage(EvoForgeProperties properties, ObjectMapper objectMapper) {
        this.properties = properties
        this.objectMapper = objectMapper
    }

    @Override
    Optional<String> readFreshCode(SkillDefinition skill) {
        if (!skill?.id || !skill.checksum) {
            return Optional.empty()
        }
        Path dir = skillDirectory(skill.id)
        Path codePath = dir.resolve('skill.groovy')
        Path checksumPath = dir.resolve('checksum')
        if (!Files.isRegularFile(codePath) || !Files.isRegularFile(checksumPath)) {
            return Optional.empty()
        }

        try {
            String localChecksum = Files.readString(checksumPath).trim()
            if (localChecksum != skill.checksum) {
                return Optional.empty()
            }
            return Optional.of(Files.readString(codePath))
        } catch (Exception ex) {
            log.debug('Failed to read local skill code {}: {}', skill.id, ex.message)
            return Optional.empty()
        }
    }

    @Override
    void writeLatest(SkillDefinition skill) {
        if (!skill?.id || skill.code == null || !skill.checksum) {
            return
        }

        try {
            Path dir = skillDirectory(skill.id)
            Files.createDirectories(dir)
            Files.writeString(dir.resolve('skill.groovy'), skill.code ?: '')
            Files.writeString(dir.resolve('checksum'), skill.checksum)
            objectMapper.writerWithDefaultPrettyPrinter().writeValue(dir.resolve('manifest.json').toFile(), [
                id        : skill.id,
                name      : skill.name,
                version   : skill.version,
                language  : skill.language,
                entryClass: skill.entryClass,
                enabled   : skill.enabled,
                status    : skill.status?.name(),
                checksum  : skill.checksum,
                updatedAt : skill.updatedAt?.toString()
            ].findAll { it.value != null })
        } catch (Exception ex) {
            log.warn('Failed to write local skill code {}: {}', skill.id, ex.message)
        }
    }

    @Override
    void delete(String skillId) {
        if (!skillId) {
            return
        }
        Path dir = skillDirectory(skillId)
        if (!Files.exists(dir)) {
            return
        }

        try {
            Files.walk(dir).withCloseable { stream ->
                stream
                    .sorted(Comparator.reverseOrder())
                    .forEach { Files.deleteIfExists(it) }
            }
        } catch (Exception ex) {
            log.warn('Failed to delete local skill code {}: {}', skillId, ex.message)
        }
    }

    private Path skillDirectory(String id) {
        return Path.of(properties.skills.codeStoragePath).normalize().resolve(safeName(id))
    }

    private static String safeName(String value) {
        return value.replaceAll('[^A-Za-z0-9._-]', '_')
    }
}
