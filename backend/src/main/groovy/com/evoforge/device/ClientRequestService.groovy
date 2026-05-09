package com.evoforge.device

import com.evoforge.agent.AgentConversationMemoryService
import com.evoforge.api.SkillCreateRequest
import com.evoforge.api.SkillExecuteRequest
import com.evoforge.api.SkillProposalRequest
import com.evoforge.api.SkillUpdateRequest
import com.evoforge.audit.SkillAuditService
import com.evoforge.audit.SkillEvent
import com.evoforge.audit.SkillEventType
import com.evoforge.history.SkillHistoryEntry
import com.evoforge.history.SkillHistoryService
import com.evoforge.learning.SelfLearningDashboardService
import com.evoforge.llm.ModelProviderConfigService
import com.evoforge.model.SkillDefinition
import com.evoforge.model.SkillResult
import com.evoforge.skills.SkillService
import com.evoforge.task.EvoTaskService
import com.evoforge.tester.TesterCapabilityService
import com.evoforge.workbench.SkillWorkbenchService
import org.springframework.stereotype.Service

@Service
class ClientRequestService {
    private final AgentConversationMemoryService conversationMemoryService
    private final DeviceTaskEventStore eventStore
    private final DeviceStatusService statusService
    private final TesterCapabilityService testerCapabilityService
    private final SkillService skillService
    private final SkillWorkbenchService workbenchService
    private final SkillHistoryService historyService
    private final SkillAuditService auditService
    private final ModelProviderConfigService modelProviderConfigService
    private final SelfLearningDashboardService selfLearningDashboardService
    private final EvoTaskService evoTaskService

    ClientRequestService(AgentConversationMemoryService conversationMemoryService,
                         DeviceTaskEventStore eventStore,
                         DeviceStatusService statusService,
                         TesterCapabilityService testerCapabilityService,
                         SkillService skillService,
                         SkillWorkbenchService workbenchService,
                         SkillHistoryService historyService,
                         SkillAuditService auditService,
                         ModelProviderConfigService modelProviderConfigService,
                         SelfLearningDashboardService selfLearningDashboardService,
                         EvoTaskService evoTaskService) {
        this.conversationMemoryService = conversationMemoryService
        this.eventStore = eventStore
        this.statusService = statusService
        this.testerCapabilityService = testerCapabilityService
        this.skillService = skillService
        this.workbenchService = workbenchService
        this.historyService = historyService
        this.auditService = auditService
        this.modelProviderConfigService = modelProviderConfigService
        this.selfLearningDashboardService = selfLearningDashboardService
        this.evoTaskService = evoTaskService
    }

    Map<String, Object> handle(DeviceCommandMessage command) {
        Map<String, Object> request = command.attributes?.request instanceof Map
            ? command.attributes.request as Map<String, Object>
            : command.attributes ?: [:]
        String method = (request.method ?: methodName(request.resource, request.action)).toString()
        Map params = request.params instanceof Map ? request.params as Map : [:]
        Object data = dispatch(method, params)
        return [
            requestId: request.requestId ?: command.taskId,
            method   : method,
            ok       : true,
            data     : data
        ] as Map<String, Object>
    }

    private Object dispatch(String method, Map params) {
        switch (method) {
            case 'agent.conversations.list':
                int limit = intParam(params.limit, 50, 1, 200)
                return conversationMemoryService.threadViews(conversationMemoryService.listThreads(limit))
            case 'agent.conversations.create':
                def thread = conversationMemoryService.createThread(
                    params.threadId?.toString(),
                    params.title?.toString(),
                    params.metadata instanceof Map ? params.metadata as Map<String, Object> : [:]
                )
                return conversationMemoryService.threadView(thread)
            case 'agent.conversations.turns':
                String threadId = required(params.threadId, 'threadId')
                int limit = intParam(params.limit, 100, 1, 200)
                return conversationMemoryService.toView(conversationMemoryService.recent(threadId, limit))
            case 'device.tasks.list':
                int limit = intParam(params.limit, 50, 1, 200)
                return eventStore.listRecentTasks(limit)
            case 'evo.tasks.list':
                int limit = intParam(params.limit, 50, 1, 200)
                return evoTaskService.listRecent(limit)
            case 'evo.tasks.get':
                return evoTaskService.find(required(params.taskId, 'taskId'))
            case 'evo.tasks.snapshot':
                return evoTaskService.snapshot(required(params.taskId, 'taskId'))
            case 'device.tasks.events':
                return eventStore.listForTask(required(params.taskId, 'taskId'))
            case 'device.tasks.events.page':
                int limit = intParam(params.limit, 80, 1, 200)
                return eventStore
                    .listForTaskPage(
                        required(params.taskId, 'taskId'),
                        limit,
                        params.before?.toString() ?: '',
                        params.after?.toString() ?: ''
                    )
                    .toMap()
            case 'device.status.get':
                return statusService.status()
            case 'skills.list':
                return skillService.list().collect { skillView(it) }
            case 'skills.get':
                return skillDetail(skillService.get(required(params.id, 'id')))
            case 'skills.create':
                return skillDetail(skillService.create(new SkillCreateRequest(
                    id: text(params.id),
                    name: text(params.name),
                    version: text(params.version),
                    language: text(params.language),
                    entryClass: text(params.entryClass),
                    code: text(params.code),
                    enabled: params.containsKey('enabled') ? boolParam(params.enabled, false) : null,
                    metadata: mapParam(params.metadata)
                )))
            case 'skills.update':
                return skillDetail(skillService.update(required(params.id, 'id'), new SkillUpdateRequest(
                    name: text(params.name),
                    version: text(params.version),
                    language: text(params.language),
                    entryClass: text(params.entryClass),
                    code: text(params.code),
                    enabled: params.containsKey('enabled') ? boolParam(params.enabled, false) : null,
                    metadata: params.containsKey('metadata') ? mapParam(params.metadata) : null
                )))
            case 'skills.activate':
                return skillDetail(skillService.activate(required(params.id, 'id')))
            case 'skills.history':
                return historyService.listForSkill(required(params.id, 'id')).collect { historyEntry(it) }
            case 'skills.audit':
                return auditService.listForSkill(required(params.id, 'id')).collect { auditEvent(it) }
            case 'skills.execute':
                SkillExecuteRequest executeRequest = new SkillExecuteRequest(
                    input: text(params.input),
                    attributes: mapParam(params.attributes),
                    llm: text(params.llm),
                    codeModel: text(params.codeModel),
                    evaluate: boolParam(params.evaluate, false)
                )
                return skillResult(skillService.execute(
                    required(params.id, 'id'),
                    executeRequest.input,
                    executeRequest.attributes,
                    executeRequest.llm,
                    executeRequest.codeModel,
                    executeRequest.evaluate
                ))
            case 'skills.propose':
                SkillProposalRequest proposalRequest = new SkillProposalRequest(
                    name: text(params.name),
                    prompt: text(params.prompt),
                    codeModel: text(params.codeModel)
                )
                def proposal = workbenchService.propose(proposalRequest)
                auditService.record(SkillEventType.PROPOSED, null, proposal.name, [prompt: proposalRequest.prompt])
                return [
                    name      : proposal.name,
                    language  : proposal.language,
                    entryClass: proposal.entryClass,
                    code      : proposal.code
                ]
            case 'models.configs.list':
                return modelProviderConfigService.list().collect { ModelProviderConfigService.toView(it) }
            case 'models.configs.save':
                return ModelProviderConfigService.toView(modelProviderConfigService.upsert(params as Map<String, Object>))
            case 'models.configs.delete':
                modelProviderConfigService.delete(required(params.id, 'id'))
                return [deleted: true]
            case 'selfLearning.dashboard':
                int limit = intParam(params.limit, 80, 1, 200)
                return selfLearningDashboardService.dashboard(limit)
            case 'selfLearning.start':
                selfLearningDashboardService.startAutonomousLearningSideQuest()
                return selfLearningDashboardService.dashboard(intParam(params.limit, 80, 1, 200))
            case 'tester.capabilities.list':
                return testerCapabilityService.list(params.projectKey?.toString() ?: '').collect { it.toView() }
            case 'tester.capabilities.discover':
                return testerCapabilityService.discover(params)
            case 'tester.capabilities.save':
                return testerCapabilityService.save(params).toView()
            case 'tester.capabilities.delete':
                testerCapabilityService.delete(required(params.projectKey, 'projectKey'), required(params.id, 'id'))
                return [deleted: true]
            default:
                throw new IllegalArgumentException("Unsupported client request method: ${method}".toString())
        }
    }

    private static String methodName(Object resource, Object action) {
        String resourceText = resource?.toString()?.trim()
        String actionText = action?.toString()?.trim()
        if (!resourceText || !actionText) {
            return ''
        }
        return "${resourceText}.${actionText}".toString()
    }

    private static String required(Object value, String field) {
        String text = value?.toString()?.trim()
        if (!text) {
            throw new IllegalArgumentException("${field} is required")
        }
        return text
    }

    private static int intParam(Object value, int defaultValue, int min, int max) {
        int parsed = value instanceof Number ? value.intValue() : value?.toString()?.isInteger() ? value.toString().toInteger() : defaultValue
        return Math.max(min, Math.min(max, parsed))
    }

    private static Map<String, Object> skillView(SkillDefinition skill) {
        return [
            id        : skill.id,
            name      : skill.name,
            version   : skill.version,
            language  : skill.language,
            entryClass: skill.entryClass,
            enabled   : skill.enabled,
            status    : skill.status,
            updatedAt : skill.updatedAt
        ] as Map<String, Object>
    }

    private static Map<String, Object> skillDetail(SkillDefinition skill) {
        Map<String, Object> view = skillView(skill)
        view.code = skill.code
        view.metadata = skill.metadata ?: [:]
        view.createdAt = skill.createdAt
        return view
    }

    private static Map<String, Object> historyEntry(SkillHistoryEntry entry) {
        return [
            id        : entry.id,
            skillId   : entry.skillId,
            name      : entry.name,
            version   : entry.version,
            language  : entry.language,
            entryClass: entry.entryClass,
            code      : entry.code,
            status    : entry.status,
            metadata  : entry.metadata ?: [:],
            createdAt : entry.createdAt
        ] as Map<String, Object>
    }

    private static Map<String, Object> auditEvent(SkillEvent event) {
        return [
            id       : event.id,
            type     : event.type,
            skillId  : event.skillId,
            skillName: event.skillName,
            timestamp: event.timestamp,
            payload  : event.payload ?: [:]
        ] as Map<String, Object>
    }

    private static Map<String, Object> skillResult(SkillResult result) {
        return [
            success   : result?.success ?: false,
            output    : result?.output,
            error     : result?.error,
            evaluation: result?.evaluation,
            meta      : result?.meta ?: [:]
        ] as Map<String, Object>
    }

    private static Map<String, Object> mapParam(Object value) {
        return value instanceof Map ? value as Map<String, Object> : [:]
    }

    private static Boolean boolParam(Object value, boolean defaultValue) {
        if (value == null) {
            return defaultValue
        }
        return value instanceof Boolean ? value : value.toString().toBoolean()
    }

    private static String text(Object value) {
        return value == null ? null : value.toString()
    }
}
