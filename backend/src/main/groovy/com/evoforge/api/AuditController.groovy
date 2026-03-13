package com.evoforge.api

import com.evoforge.audit.SkillAuditService
import com.evoforge.audit.SkillEvent
import org.springframework.web.bind.annotation.*

@RestController
@RequestMapping('/api/audit')
class AuditController {
    private final SkillAuditService auditService

    AuditController(SkillAuditService auditService) {
        this.auditService = auditService
    }

    @GetMapping
    List<SkillEvent> listAll() {
        return auditService.listAll()
    }

    @GetMapping('{skillId}')
    List<SkillEvent> listForSkill(@PathVariable String skillId) {
        return auditService.listForSkill(skillId)
    }
}
