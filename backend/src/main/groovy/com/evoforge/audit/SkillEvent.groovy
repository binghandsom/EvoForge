package com.evoforge.audit

import groovy.transform.ToString

import java.time.Instant

@ToString(includeNames = true)
class SkillEvent {
    String id
    SkillEventType type
    String skillId
    String skillName
    Instant timestamp = Instant.now()
    Map<String, Object> payload = [:]
}
