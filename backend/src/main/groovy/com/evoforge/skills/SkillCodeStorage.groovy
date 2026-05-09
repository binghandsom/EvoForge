package com.evoforge.skills

import com.evoforge.model.SkillDefinition

interface SkillCodeStorage {
    Optional<String> readFreshCode(SkillDefinition skill)
    void writeLatest(SkillDefinition skill)
    void delete(String skillId)
}
