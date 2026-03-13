package com.evoforge.skills

import com.evoforge.api.SkillCreateRequest
import com.evoforge.api.SkillUpdateRequest
import com.evoforge.audit.SkillAuditService
import com.evoforge.audit.SkillEventType
import com.evoforge.core.SkillChecksum
import com.evoforge.history.SkillHistoryService
import com.evoforge.llm.ModelHub
import com.evoforge.model.SkillContext
import com.evoforge.model.SkillDefinition
import com.evoforge.model.SkillResult
import com.evoforge.model.SkillStatus
import com.evoforge.policy.SkillPolicy
import com.evoforge.evaluation.SkillEvaluationService
import com.evoforge.metrics.SkillMetricsService
import com.evoforge.store.SkillStore
import org.springframework.stereotype.Service

import java.time.Instant

@Service
class SkillService {
    private final SkillStore store
    private final SkillCompiler compiler
    private final SkillRegistry registry
    private final ModelHub modelHub
    private final SkillPolicy policy
    private final SkillHistoryService historyService
    private final SkillAuditService auditService
    private final SkillEvaluationService evaluationService
    private final SkillMetricsService metricsService

    SkillService(SkillStore store,
                 SkillCompiler compiler,
                 SkillRegistry registry,
                 ModelHub modelHub,
                 SkillPolicy policy,
                 SkillHistoryService historyService,
                 SkillAuditService auditService,
                 SkillEvaluationService evaluationService,
                 SkillMetricsService metricsService) {
        this.store = store
        this.compiler = compiler
        this.registry = registry
        this.modelHub = modelHub
        this.policy = policy
        this.historyService = historyService
        this.auditService = auditService
        this.evaluationService = evaluationService
        this.metricsService = metricsService
    }

    List<SkillDefinition> list() {
        return store.loadAll()
    }

    SkillDefinition get(String id) {
        return store.findById(id).orElseThrow { new IllegalArgumentException("Skill not found: ${id}") }
    }

    SkillDefinition create(SkillCreateRequest input) {
        boolean enabled = input.enabled != null ? input.enabled : false
        def skill = new SkillDefinition(
            id: input.id ?: UUID.randomUUID().toString(),
            name: input.name,
            version: input.version ?: '0.1.0',
            language: input.language ?: 'groovy',
            entryClass: input.entryClass,
            code: input.code,
            enabled: enabled,
            status: input.status ?: (enabled ? SkillStatus.ACTIVE : SkillStatus.DRAFT),
            metadata: input.metadata ?: [:],
            createdAt: Instant.now(),
            updatedAt: Instant.now()
        )
        validateAndCompile(skill, false)
        store.save(skill)
        historyService.snapshot(skill)
        auditService.record(SkillEventType.CREATED, skill, [version: skill.version])
        return skill
    }

    SkillDefinition update(String id, SkillUpdateRequest update) {
        SkillDefinition existing = get(id)
        boolean shouldSnapshot = (update.code && update.code != existing.code) ||
            (update.version && update.version != existing.version) ||
            (update.entryClass && update.entryClass != existing.entryClass) ||
            (update.metadata && update.metadata != existing.metadata)
        if (shouldSnapshot) {
            historyService.snapshot(existing)
        }
        existing.name = update.name ?: existing.name
        existing.version = update.version ?: existing.version
        existing.language = update.language ?: existing.language
        existing.entryClass = update.entryClass ?: existing.entryClass
        existing.code = update.code ?: existing.code
        existing.metadata = update.metadata ?: existing.metadata
        if (update.status != null) {
            existing.status = update.status
        }
        if (update.enabled != null) {
            existing.enabled = update.enabled
            if (update.enabled) {
                existing.status = SkillStatus.ACTIVE
            } else if (existing.status == SkillStatus.ACTIVE) {
                existing.status = SkillStatus.DRAFT
            }
        }
        existing.updatedAt = Instant.now()

        validateAndCompile(existing, existing.enabled)
        store.save(existing)
        auditService.record(SkillEventType.UPDATED, existing, [version: existing.version])
        if (existing.enabled) {
            registry.refreshFromStore(true)
        }
        return existing
    }

    SkillDefinition activate(String id) {
        SkillDefinition existing = get(id)
        existing.enabled = true
        existing.status = SkillStatus.ACTIVE
        existing.updatedAt = Instant.now()
        validateAndCompile(existing, true)
        store.save(existing)
        auditService.record(SkillEventType.ACTIVATED, existing, [:])
        return existing
    }

    SkillDefinition deactivate(String id) {
        SkillDefinition existing = get(id)
        existing.enabled = false
        if (existing.status == SkillStatus.ACTIVE) {
            existing.status = SkillStatus.DRAFT
        }
        existing.updatedAt = Instant.now()
        store.save(existing)
        registry.remove(existing.id)
        auditService.record(SkillEventType.DEACTIVATED, existing, [:])
        return existing
    }

    SkillDefinition rollback(String id, String historyId) {
        def entry = historyService.get(historyId)
        if (!entry || entry.skillId != id) {
            throw new IllegalArgumentException("History entry not found for skill: ${id}")
        }
        SkillDefinition existing = get(id)
        historyService.snapshot(existing)
        existing.name = entry.name
        existing.version = entry.version
        existing.language = entry.language
        existing.entryClass = entry.entryClass
        existing.code = entry.code
        if (entry.status != null) {
            existing.status = entry.status
        }
        existing.metadata = entry.metadata ?: [:]
        existing.updatedAt = Instant.now()
        validateAndCompile(existing, existing.enabled)
        store.save(existing)
        auditService.record(SkillEventType.ROLLED_BACK, existing, [historyId: historyId])
        if (existing.enabled) {
            registry.refreshFromStore(true)
        }
        return existing
    }

    SkillResult execute(String id,
                        String input,
                        Map<String, Object> attributes = [:],
                        String llm = null,
                        String codeModel = null,
                        boolean evaluate = false) {
        SkillDefinition skill = get(id)
        if (!skill.enabled) {
            return SkillResult.fail('Skill is disabled')
        }
        SkillRuntimeEntry entry = registry.get(id)
        if (!entry) {
            validateAndCompile(skill, true)
            entry = registry.get(id)
        }
        if (!entry) {
            return SkillResult.fail('Skill not loaded')
        }
        Skill instance = entry.skillClass.getDeclaredConstructor().newInstance()
        def context = new SkillContext(
            input: input,
            attributes: attributes ?: [:],
            llm: modelHub.getLlm(llm),
            codeModel: modelHub.getCodeModel(codeModel)
        )
        Instant start = Instant.now()
        try {
            def result = instance.execute(context)
            if (evaluate) {
                result.evaluation = evaluationService.evaluate(skill, input, result, llm)
            }
            metricsService.recordExecution(skill.id, result?.success, durationMs(start))
            auditService.record(SkillEventType.EXECUTED, skill, [
                durationMs: durationMs(start),
                success: result?.success,
                score: result?.evaluation?.score
            ])
            return result
        } catch (Exception ex) {
            metricsService.recordExecution(skill.id, false, durationMs(start))
            auditService.record(SkillEventType.FAILED, skill, [
                durationMs: durationMs(start),
                error: ex.message
            ])
            throw ex
        }
    }

    private void validateAndCompile(SkillDefinition skill, boolean register) {
        def violations = policy.validate(skill)
        if (violations && !violations.isEmpty()) {
            throw new IllegalArgumentException("Skill policy violations: ${violations.join('; ')}")
        }
        skill.checksum = SkillChecksum.sha256(skill.code)
        Class<? extends Skill> clazz = compiler.compile(skill)
        if (!skill.entryClass) {
            skill.entryClass = clazz.name
        }
        if (register) {
            registry.register(skill, clazz)
        }
    }

    private static long durationMs(Instant start) {
        return Math.max(0L, Instant.now().toEpochMilli() - start.toEpochMilli())
    }
}
