package com.evoforge.audit

import com.evoforge.model.SkillDefinition
import org.springframework.stereotype.Service

import java.time.Instant

@Service
class SkillAuditService {
    private final SkillAuditStore store

    SkillAuditService(SkillAuditStore store) {
        this.store = store
    }

    SkillEvent record(SkillEventType type, SkillDefinition skill, Map<String, Object> payload = [:]) {
        return record(type, skill?.id, skill?.name, payload)
    }

    SkillEvent record(SkillEventType type, String skillId, String skillName, Map<String, Object> payload = [:]) {
        def event = new SkillEvent(
            id: UUID.randomUUID().toString(),
            type: type,
            skillId: skillId,
            skillName: skillName,
            timestamp: Instant.now(),
            payload: payload ?: [:]
        )
        store.append(event)
        return event
    }

    List<SkillEvent> listAll() {
        return store.loadAll()
    }

    List<SkillEvent> listForSkill(String skillId) {
        return store.listForSkill(skillId)
    }
}
