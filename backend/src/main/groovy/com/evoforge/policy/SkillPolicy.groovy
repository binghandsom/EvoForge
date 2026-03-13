package com.evoforge.policy

import com.evoforge.model.SkillDefinition

interface SkillPolicy {
    List<String> validate(SkillDefinition skill)
}
