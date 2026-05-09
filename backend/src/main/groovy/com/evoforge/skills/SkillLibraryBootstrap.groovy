package com.evoforge.skills

import com.evoforge.core.EvoForgeProperties
import com.evoforge.model.SkillDefinition
import com.evoforge.store.SkillStore
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component

import jakarta.annotation.PostConstruct

@Component
class SkillLibraryBootstrap {
    private static final Logger log = LoggerFactory.getLogger(SkillLibraryBootstrap)

    private final SkillStore store
    private final SkillLibraryService libraryService
    private final EvoForgeProperties properties

    SkillLibraryBootstrap(SkillStore store, SkillLibraryService libraryService, EvoForgeProperties properties) {
        this.store = store
        this.libraryService = libraryService
        this.properties = properties
    }

    @PostConstruct
    void importWhenStoreIsEmpty() {
        if (!properties.skills.gitLibraryEnabled || !properties.skills.gitLibraryBootstrapOnEmpty) {
            return
        }
        if (!store.loadAllSummaries().isEmpty()) {
            return
        }

        List<SkillDefinition> archived = libraryService.loadArchivedSkills()
        if (archived.isEmpty()) {
            return
        }

        archived.each { store.save(it) }
        log.info('Imported {} skills from git library {}', archived.size(), libraryService.libraryRoot())
    }
}
