package com.evoforge.api

import com.evoforge.model.SkillStatus
import java.time.Instant

class SkillDetailView {
    String id
    String name
    String version
    String language
    String entryClass
    boolean enabled
    SkillStatus status
    String code
    Map<String, Object> metadata
    Instant createdAt
    Instant updatedAt
}
