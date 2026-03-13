package com.evoforge.model

import groovy.transform.ToString
import java.time.Instant

@ToString(includeNames = true)
class SkillDefinition {
    String id
    String name
    String version
    String language
    String entryClass
    String code
    boolean enabled = false
    SkillStatus status = SkillStatus.DRAFT
    String checksum
    Map<String, Object> metadata = [:]
    Instant createdAt = Instant.now()
    Instant updatedAt = Instant.now()
}
