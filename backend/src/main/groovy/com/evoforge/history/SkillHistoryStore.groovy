package com.evoforge.history

interface SkillHistoryStore {
    List<SkillHistoryEntry> loadAll()
    void append(SkillHistoryEntry entry)
    List<SkillHistoryEntry> listForSkill(String skillId)
    SkillHistoryEntry findById(String id)
}
