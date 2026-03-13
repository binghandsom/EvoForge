package com.evoforge.store

import com.evoforge.model.SkillDefinition

interface SkillStore {
    List<SkillDefinition> loadAll()
    Optional<SkillDefinition> findById(String id)
    SkillDefinition save(SkillDefinition skill)
    void delete(String id)
}
