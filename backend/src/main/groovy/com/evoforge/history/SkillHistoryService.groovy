package com.evoforge.history

import com.evoforge.model.SkillDefinition
import org.springframework.stereotype.Service

@Service
class SkillHistoryService {
    private final SkillHistoryStore store

    SkillHistoryService(SkillHistoryStore store) {
        this.store = store
    }

    SkillHistoryEntry snapshot(SkillDefinition skill) {
        def entry = new SkillHistoryEntry(
            id: UUID.randomUUID().toString(),
            skillId: skill.id,
            name: skill.name,
            version: skill.version,
            language: skill.language,
            entryClass: skill.entryClass,
            code: skill.code,
            status: skill.status,
            metadata: skill.metadata ?: [:]
        )
        store.append(entry)
        return entry
    }

    List<SkillHistoryEntry> listForSkill(String skillId) {
        return store.listForSkill(skillId)
    }

    SkillHistoryEntry get(String id) {
        return store.findById(id)
    }
}
