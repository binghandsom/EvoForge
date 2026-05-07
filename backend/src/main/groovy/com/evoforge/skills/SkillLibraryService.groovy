package com.evoforge.skills

import com.evoforge.core.EvoForgeProperties
import com.evoforge.model.SkillDefinition
import com.evoforge.model.SkillStatus
import com.fasterxml.jackson.core.type.TypeReference
import com.fasterxml.jackson.databind.ObjectMapper
import org.springframework.beans.factory.ObjectProvider
import org.springframework.jdbc.core.JdbcTemplate
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service

import java.nio.file.Files
import java.nio.file.Path
import java.sql.Timestamp
import java.time.Instant

@Service
class SkillLibraryService {
    private static final Logger log = LoggerFactory.getLogger(SkillLibraryService)
    private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<Map<String, Object>>() {}

    private final ObjectMapper objectMapper
    private final EvoForgeProperties properties
    private final ObjectProvider<JdbcTemplate> jdbcTemplateProvider

    SkillLibraryService(ObjectMapper objectMapper, EvoForgeProperties properties, ObjectProvider<JdbcTemplate> jdbcTemplateProvider) {
        this.objectMapper = objectMapper
        this.properties = properties
        this.jdbcTemplateProvider = jdbcTemplateProvider
    }

    void exportSkill(SkillDefinition skill) {
        if (!properties.skills.gitLibraryEnabled || !skill?.id) {
            return
        }

        try {
            Path skillDir = skillDirectory(skill.id)
            Files.createDirectories(skillDir)
            Files.writeString(skillDir.resolve('skill.groovy'), skill.code ?: '')
            objectMapper.writerWithDefaultPrettyPrinter().writeValue(skillDir.resolve('manifest.json').toFile(), manifestFor(skill))
            Path readme = skillDir.resolve('SKILL.md')
            if (!Files.exists(readme)) {
                Files.writeString(readme, markdownFor(skill))
            }
            updateGitRef(skill, skillDir)
        } catch (Exception ex) {
            log.warn('Failed to export skill {} to git library: {}', skill.id, ex.message)
        }
    }

    List<SkillDefinition> loadArchivedSkills() {
        if (!properties.skills.gitLibraryEnabled) {
            return []
        }

        Path root = libraryRoot()
        if (!Files.isDirectory(root)) {
            return []
        }

        List<SkillDefinition> skills = []
        Files.list(root).withCloseable { stream ->
            stream
                .filter { Files.isDirectory(it) }
                .forEach { Path skillDir ->
                    Optional<SkillDefinition> skill = readSkill(skillDir)
                    if (skill.present) {
                        skills << skill.get()
                    }
                }
        }
        return skills
    }

    Path libraryRoot() {
        return Path.of(properties.skills.gitLibraryPath).normalize()
    }

    private Optional<SkillDefinition> readSkill(Path skillDir) {
        Path manifestPath = skillDir.resolve('manifest.json')
        Path groovyPath = skillDir.resolve('skill.groovy')
        if (!Files.isRegularFile(manifestPath) || !Files.isRegularFile(groovyPath)) {
            return Optional.empty()
        }

        try {
            Map<String, Object> manifest = objectMapper.readValue(manifestPath.toFile(), MAP_TYPE)
            String status = manifest.status as String
            return Optional.of(new SkillDefinition(
                id: manifest.id as String,
                name: manifest.name as String,
                version: (manifest.version ?: '0.1.0') as String,
                language: (manifest.language ?: 'groovy') as String,
                entryClass: manifest.entryClass as String,
                code: Files.readString(groovyPath),
                enabled: Boolean.TRUE == manifest.enabled,
                status: status ? SkillStatus.valueOf(status) : SkillStatus.DRAFT,
                checksum: manifest.checksum as String,
                metadata: (manifest.metadata ?: [:]) as Map<String, Object>,
                createdAt: parseInstant(manifest.createdAt),
                updatedAt: parseInstant(manifest.updatedAt)
            ))
        } catch (Exception ex) {
            log.warn('Failed to read archived skill from {}: {}', skillDir, ex.message)
            return Optional.empty()
        }
    }

    private Path skillDirectory(String id) {
        return libraryRoot().resolve(safeName(id))
    }

    private void updateGitRef(SkillDefinition skill, Path skillDir) {
        JdbcTemplate jdbcTemplate = jdbcTemplateProvider.ifAvailable
        if (!jdbcTemplate) {
            return
        }
        jdbcTemplate.update('''
            INSERT INTO skill_git_refs (
                skill_id, repo_path, manifest_path, groovy_path, commit_hash, synced_checksum, synced_at
            )
            VALUES (?, ?, ?, ?, ?, ?, ?)
            ON CONFLICT (skill_id) DO UPDATE SET
                repo_path = EXCLUDED.repo_path,
                manifest_path = EXCLUDED.manifest_path,
                groovy_path = EXCLUDED.groovy_path,
                synced_checksum = EXCLUDED.synced_checksum,
                synced_at = EXCLUDED.synced_at
        ''',
            skill.id,
            libraryRoot().toString(),
            skillDir.resolve('manifest.json').toString(),
            skillDir.resolve('skill.groovy').toString(),
            null,
            skill.checksum,
            Timestamp.from(Instant.now())
        )
    }

    private static String safeName(String value) {
        return value.replaceAll('[^A-Za-z0-9._-]', '_')
    }

    private static Map<String, Object> manifestFor(SkillDefinition skill) {
        return [
            id        : skill.id,
            name      : skill.name,
            version   : skill.version,
            language  : skill.language ?: 'groovy',
            entryClass: skill.entryClass,
            enabled   : skill.enabled,
            status    : (skill.status ?: SkillStatus.DRAFT).name(),
            checksum  : skill.checksum,
            metadata  : skill.metadata ?: [:],
            createdAt : skill.createdAt?.toString(),
            updatedAt : skill.updatedAt?.toString()
        ]
    }

    private static String markdownFor(SkillDefinition skill) {
        return """# ${skill.name ?: skill.id}

## Purpose
Describe when this skill should be used.

## Runtime
- Skill id: `${skill.id}`
- Entry class: `${skill.entryClass ?: ''}`
- Language: `${skill.language ?: 'groovy'}`

## Notes
Add usage notes, constraints, examples, and review decisions here.
"""
    }

    private static Instant parseInstant(Object value) {
        return value ? Instant.parse(value as String) : Instant.now()
    }
}
