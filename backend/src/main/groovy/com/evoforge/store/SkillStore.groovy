package com.evoforge.store

import com.evoforge.model.SkillDefinition

interface SkillStore {
    List<SkillDefinition> loadAll()
    List<SkillDefinition> loadAllSummaries()
    Optional<SkillDefinition> findById(String id)
    Optional<SkillDefinition> findSummaryById(String id)
    SkillDefinition save(SkillDefinition skill)
    void delete(String id)
}
