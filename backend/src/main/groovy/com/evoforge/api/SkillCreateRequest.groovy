package com.evoforge.api

import com.evoforge.model.SkillStatus

class SkillCreateRequest {
    String id
    String name
    String version
    String language
    String entryClass
    String code
    Boolean enabled
    SkillStatus status
    Map<String, Object> metadata
}
