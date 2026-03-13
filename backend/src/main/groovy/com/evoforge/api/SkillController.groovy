package com.evoforge.api

import com.evoforge.audit.SkillAuditService
import com.evoforge.audit.SkillEventType
import com.evoforge.history.SkillHistoryEntry
import com.evoforge.history.SkillHistoryService
import com.evoforge.model.SkillDefinition
import com.evoforge.model.SkillResult
import com.evoforge.skills.SkillService
import com.evoforge.workbench.SkillWorkbenchService
import org.springframework.web.bind.annotation.*

@RestController
@RequestMapping('/api/skills')
class SkillController {
    private final SkillService skillService
    private final SkillWorkbenchService workbenchService
    private final SkillHistoryService historyService
    private final SkillAuditService auditService

    SkillController(SkillService skillService,
                    SkillWorkbenchService workbenchService,
                    SkillHistoryService historyService,
                    SkillAuditService auditService) {
        this.skillService = skillService
        this.workbenchService = workbenchService
        this.historyService = historyService
        this.auditService = auditService
    }

    @GetMapping
    List<SkillView> list() {
        return skillService.list().collect { toView(it) }
    }

    @GetMapping('{id}')
    SkillDetailView get(@PathVariable String id) {
        return toDetail(skillService.get(id))
    }

    @PostMapping
    SkillDetailView create(@RequestBody SkillCreateRequest request) {
        return toDetail(skillService.create(request))
    }

    @PutMapping('{id}')
    SkillDetailView update(@PathVariable String id, @RequestBody SkillUpdateRequest request) {
        return toDetail(skillService.update(id, request))
    }

    @PostMapping('{id}/activate')
    SkillDetailView activate(@PathVariable String id) {
        return toDetail(skillService.activate(id))
    }

    @PostMapping('{id}/deactivate')
    SkillDetailView deactivate(@PathVariable String id) {
        return toDetail(skillService.deactivate(id))
    }

    @PostMapping('{id}/execute')
    SkillResult execute(@PathVariable String id, @RequestBody SkillExecuteRequest request) {
        return skillService.execute(id, request.input, request.attributes ?: [:], request.llm, request.codeModel, request.evaluate)
    }

    @GetMapping('{id}/history')
    List<SkillHistoryEntry> history(@PathVariable String id) {
        return historyService.listForSkill(id)
    }

    @PostMapping('{id}/rollback')
    SkillDetailView rollback(@PathVariable String id, @RequestBody SkillRollbackRequest request) {
        return toDetail(skillService.rollback(id, request.historyId))
    }

    @PostMapping('propose')
    SkillProposalResponse propose(@RequestBody SkillProposalRequest request) {
        def proposal = workbenchService.propose(request)
        auditService.record(SkillEventType.PROPOSED, null, proposal.name, [prompt: request.prompt])
        return proposal
    }

    @PostMapping('evolve')
    SkillDetailView evolve(@RequestBody SkillProposalRequest request) {
        def proposal = workbenchService.propose(request)
        def createRequest = new SkillCreateRequest(
            name: proposal.name,
            language: proposal.language,
            entryClass: proposal.entryClass,
            code: proposal.code,
            enabled: false
        )
        def created = skillService.create(createRequest)
        return toDetail(created)
    }

    private SkillView toView(SkillDefinition skill) {
        return new SkillView(
            id: skill.id,
            name: skill.name,
            version: skill.version,
            language: skill.language,
            entryClass: skill.entryClass,
            enabled: skill.enabled,
            status: skill.status,
            updatedAt: skill.updatedAt
        )
    }

    private SkillDetailView toDetail(SkillDefinition skill) {
        return new SkillDetailView(
            id: skill.id,
            name: skill.name,
            version: skill.version,
            language: skill.language,
            entryClass: skill.entryClass,
            enabled: skill.enabled,
            status: skill.status,
            code: skill.code,
            metadata: skill.metadata,
            createdAt: skill.createdAt,
            updatedAt: skill.updatedAt
        )
    }
}
