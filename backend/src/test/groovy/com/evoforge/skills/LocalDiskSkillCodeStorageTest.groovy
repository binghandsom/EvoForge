package com.evoforge.skills

import com.evoforge.core.EvoForgeProperties
import com.evoforge.core.SkillChecksum
import com.evoforge.model.SkillDefinition
import com.evoforge.model.SkillStatus
import com.fasterxml.jackson.databind.ObjectMapper
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

import java.nio.file.Files
import java.nio.file.Path

import static org.junit.jupiter.api.Assertions.*

class LocalDiskSkillCodeStorageTest {

    @TempDir
    Path tempDir

    @Test
    void readsCodeOnlyWhenLocalChecksumMatchesDefinitionChecksum() {
        EvoForgeProperties properties = new EvoForgeProperties()
        properties.skills.codeStoragePath = tempDir.resolve('skill-code-cache').toString()
        LocalDiskSkillCodeStorage storage = new LocalDiskSkillCodeStorage(properties, new ObjectMapper())
        String code = 'class DemoSkill {}'
        SkillDefinition skill = new SkillDefinition(
            id: 'demo/skill',
            name: 'Demo Skill',
            version: '0.1.0',
            language: 'groovy',
            entryClass: 'DemoSkill',
            code: code,
            enabled: true,
            status: SkillStatus.ACTIVE,
            checksum: SkillChecksum.sha256(code)
        )

        storage.writeLatest(skill)

        Optional<String> fresh = storage.readFreshCode(skill)
        assertTrue(fresh.present)
        assertEquals(code, fresh.get())
        assertTrue(Files.isRegularFile(tempDir.resolve('skill-code-cache/demo_skill/skill.groovy')))
        assertTrue(Files.isRegularFile(tempDir.resolve('skill-code-cache/demo_skill/checksum')))

        skill.checksum = SkillChecksum.sha256('different code')

        assertTrue(storage.readFreshCode(skill).empty)
    }

    @Test
    void deletesSkillCodeDirectory() {
        EvoForgeProperties properties = new EvoForgeProperties()
        properties.skills.codeStoragePath = tempDir.resolve('skill-code-cache').toString()
        LocalDiskSkillCodeStorage storage = new LocalDiskSkillCodeStorage(properties, new ObjectMapper())
        SkillDefinition skill = new SkillDefinition(
            id: 'demo',
            code: 'class DemoSkill {}',
            checksum: SkillChecksum.sha256('class DemoSkill {}')
        )
        storage.writeLatest(skill)

        storage.delete('demo')

        assertFalse(Files.exists(tempDir.resolve('skill-code-cache/demo')))
    }
}
