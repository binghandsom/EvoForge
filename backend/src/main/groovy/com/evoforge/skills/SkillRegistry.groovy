package com.evoforge.skills

import com.evoforge.core.EvoForgeProperties
import com.evoforge.core.SkillChecksum
import com.evoforge.model.SkillDefinition
import com.evoforge.store.SkillStore
import org.springframework.context.annotation.DependsOn
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component

import jakarta.annotation.PostConstruct
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap

@Component
@DependsOn('skillLibraryBootstrap')
class SkillRegistry {
    private static final Logger log = LoggerFactory.getLogger(SkillRegistry)

    private final SkillStore store
    private final SkillCompiler compiler
    private final EvoForgeProperties properties
    private final Map<String, SkillRuntimeEntry> registry = new ConcurrentHashMap<>()

    SkillRegistry(SkillStore store, SkillCompiler compiler, EvoForgeProperties properties) {
        this.store = store
        this.compiler = compiler
        this.properties = properties
    }

    @PostConstruct
    void init() {
        refreshFromStore(true)
    }

    @Scheduled(fixedDelayString = '${evoforge.skills.autoReloadSeconds:5}000')
    void scheduledRefresh() {
        refreshFromStore(false)
    }

    void refreshFromStore(boolean force) {
        List<SkillDefinition> all = store.loadAllSummaries()
        Set<String> activeIds = [] as Set

        all.each { skill ->
            activeIds << skill.id
            if (!skill.enabled) {
                registry.remove(skill.id)
                return
            }

            String checksum = skill.checksum
            def existing = registry.get(skill.id)
            if (checksum && !force && existing && existing.definition?.checksum == checksum) {
                return
            }

            try {
                SkillDefinition fullSkill = skill.code ? skill : store.findById(skill.id).orElse(skill)
                checksum = fullSkill.checksum ?: SkillChecksum.sha256(fullSkill.code)
                Class<? extends Skill> clazz = compiler.compile(fullSkill)
                fullSkill.checksum = checksum
                fullSkill.updatedAt = Instant.now()
                registry.put(fullSkill.id, new SkillRuntimeEntry(definition: fullSkill, skillClass: clazz, compiledAt: Instant.now()))
                log.info('Loaded skill {} ({})', fullSkill.name, fullSkill.id)
            } catch (Exception ex) {
                log.warn('Failed to load skill {}: {}', skill.id, ex.message)
            }
        }

        registry.keySet().findAll { !activeIds.contains(it) }.each { registry.remove(it) }
    }

    SkillRuntimeEntry get(String id) {
        return registry.get(id)
    }

    List<SkillRuntimeEntry> list() {
        return registry.values().toList()
    }

    void register(SkillDefinition skill, Class<? extends Skill> clazz) {
        registry.put(skill.id, new SkillRuntimeEntry(definition: skill, skillClass: clazz, compiledAt: Instant.now()))
    }

    void remove(String id) {
        registry.remove(id)
    }
}
