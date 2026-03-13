package com.evoforge.api

import com.evoforge.model.SkillStatus
import java.time.Instant

class SkillView {
    String id
    String name
    String version
    String language
    String entryClass
    boolean enabled
    SkillStatus status
    Instant updatedAt
}
