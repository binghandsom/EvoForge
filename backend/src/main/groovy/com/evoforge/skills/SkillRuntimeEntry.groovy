package com.evoforge.skills

import com.evoforge.model.SkillDefinition

import java.time.Instant

class SkillRuntimeEntry {
    SkillDefinition definition
    Class<? extends Skill> skillClass
    Instant compiledAt = Instant.now()
}
