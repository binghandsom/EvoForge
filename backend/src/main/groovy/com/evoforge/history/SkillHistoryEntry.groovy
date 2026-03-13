package com.evoforge.history

import com.evoforge.model.SkillStatus
import groovy.transform.ToString

import java.time.Instant

@ToString(includeNames = true)
class SkillHistoryEntry {
    String id
    String skillId
    String name
    String version
    String language
    String entryClass
    String code
    SkillStatus status
    Map<String, Object> metadata = [:]
    Instant createdAt = Instant.now()
}
