package com.evoforge.audit

interface SkillAuditStore {
    List<SkillEvent> loadAll()
    void append(SkillEvent event)
    List<SkillEvent> listForSkill(String skillId)
    void clear()
}
