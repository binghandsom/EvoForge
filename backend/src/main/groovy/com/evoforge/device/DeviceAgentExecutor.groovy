package com.evoforge.device

import com.evoforge.api.AgentRequest
import com.evoforge.core.AgentService
import com.evoforge.core.EvoForgeProperties
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service

@Service
class DeviceAgentExecutor {
    private static final Logger log = LoggerFactory.getLogger(DeviceAgentExecutor)

    private final AgentService agentService
    private final CodexTaskExecutor codexTaskExecutor
    private final DeviceTaskApprovalService approvalService
    private final DeviceEventPublisher eventPublisher
    private final EvoForgeProperties properties

    DeviceAgentExecutor(AgentService agentService,
                        CodexTaskExecutor codexTaskExecutor,
                        DeviceTaskApprovalService approvalService,
                        DeviceEventPublisher eventPublisher,
                        EvoForgeProperties properties) {
        this.agentService = agentService
        this.codexTaskExecutor = codexTaskExecutor
        this.approvalService = approvalService
        this.eventPublisher = eventPublisher
        this.properties = properties
    }

    void handle(DeviceCommandMessage command) {
        DeviceCommandMessage normalized = normalize(command)
        if (!isForThisDevice(normalized)) {
            log.warn('Ignored command {} for unexpected device {}', normalized.taskId, normalized.deviceId)
            return
        }

        if (!DeviceProtocol.isCommandTypeSupported(normalized.type)) {
            publish(
                normalized,
                DeviceProtocol.STATUS_FAILED,
                DeviceProtocol.STATUS_FAILED,
                'error',
                "Unsupported command type: ${normalized.type}".toString(),
                null,
                [commandType: normalized.type],
                false
            )
            return
        }

        if (normalized.type == DeviceProtocol.TYPE_APPROVAL_DECISION) {
            handleApprovalDecision(normalized)
            return
        }

        publish(normalized, DeviceProtocol.STATUS_ACCEPTED, DeviceProtocol.STATUS_ACCEPTED, 'info', 'Command accepted', null, [:], false)

        if (requiresApproval(normalized)) {
            publish(
                normalized,
                DeviceProtocol.STATUS_NEEDS_APPROVAL,
                DeviceProtocol.STATUS_NEEDS_APPROVAL,
                'warn',
                'Command requires mobile approval before execution',
                null,
                DeviceCommandPayload.from(normalized),
                true
            )
            return
        }

        try {
            publish(normalized, DeviceProtocol.STATUS_RUNNING, DeviceProtocol.STATUS_RUNNING, 'info', 'Agent execution started', null, [:], false)
            if (normalized.type == DeviceProtocol.TYPE_CODEX_TASK) {
                runCodexTask(normalized)
                return
            }
            def response = agentService.respond(new AgentRequest(
                input: normalized.text,
                skillId: normalized.skillId,
                llm: normalized.llm,
                codeModel: normalized.codeModel,
                attributes: agentAttributes(normalized)
            ))
            publish(
                normalized,
                DeviceProtocol.STATUS_COMPLETED,
                DeviceProtocol.STATUS_COMPLETED,
                'info',
                'Agent execution completed',
                response.output,
                [skillResult: response.skillResult, agentRun: response.agentRun].findAll { it.value != null },
                false
            )
        } catch (Exception ex) {
            log.warn('Device command {} failed: {}', normalized.taskId, ex.message, ex)
            publish(
                normalized,
                DeviceProtocol.STATUS_FAILED,
                DeviceProtocol.STATUS_FAILED,
                'error',
                ex.message ?: ex.class.simpleName,
                null,
                [errorType: ex.class.name],
                true
            )
        }
    }

    private void runCodexTask(DeviceCommandMessage command) {
        CodexTaskResult result = codexTaskExecutor.execute(command)
        String level = result.status == DeviceProtocol.STATUS_FAILED ? 'error' : result.status == DeviceProtocol.STATUS_NEEDS_APPROVAL ? 'warn' : 'info'
        publish(
            command,
            result.status,
            result.status,
            level,
            result.message,
            result.output,
            [commandType: command.type],
            result.recoverable
        )
    }

    private void handleApprovalDecision(DeviceCommandMessage command) {
        try {
            String decision = (command.attributes?.decision ?: command.text ?: '').toString()
            String actor = (command.attributes?.actor ?: 'mobile').toString()
            String note = command.attributes?.note?.toString()
            DeviceTaskApprovalResult result = approvalService.applyDecision(command.taskId, decision, actor, note, false)
            if (result.approved && result.command) {
                handle(result.command)
            }
        } catch (Exception ex) {
            log.warn('Approval decision {} failed: {}', command.taskId, ex.message, ex)
            publish(
                command,
                DeviceProtocol.STATUS_FAILED,
                DeviceProtocol.STATUS_FAILED,
                'error',
                ex.message ?: ex.class.simpleName,
                null,
                [errorType: ex.class.name, commandType: command.type],
                true
            )
        }
    }

    private DeviceCommandMessage normalize(DeviceCommandMessage command) {
        if (!command) {
            command = new DeviceCommandMessage()
        }
        command.taskId = command.taskId ?: UUID.randomUUID().toString()
        command.userId = command.userId ?: properties.deviceAgent.userId
        command.deviceId = command.deviceId ?: properties.deviceAgent.deviceId
        command.type = command.type ?: DeviceProtocol.TYPE_NATURAL_LANGUAGE_TASK
        command.attributes = command.attributes ?: [:]
        return command
    }

    private boolean requiresApproval(DeviceCommandMessage command) {
        if (command.attributes?.approvalGranted == true) {
            return false
        }
        return command.requiresApproval || (command.type == DeviceProtocol.TYPE_CODEX_TASK && properties.codexTask.requiresApproval)
    }

    private boolean isForThisDevice(DeviceCommandMessage command) {
        return !command.deviceId || command.deviceId == properties.deviceAgent.deviceId
    }

    private static Map<String, Object> agentAttributes(DeviceCommandMessage command) {
        Map<String, Object> attributes = new LinkedHashMap<>(command.attributes ?: [:])
        attributes.taskId = command.taskId
        attributes.userId = command.userId
        attributes.deviceId = command.deviceId
        return attributes
    }

    private DeviceTaskEvent publish(DeviceCommandMessage command,
                                    String type,
                                    String status,
                                    String level,
                                    String message,
                                    String output,
                                    Map<String, Object> payload,
                                    boolean recoverable) {
        return eventPublisher.publish(new DeviceTaskEvent(
            taskId: command.taskId,
            userId: command.userId,
            deviceId: properties.deviceAgent.deviceId,
            type: type,
            status: status,
            level: level,
            message: message,
            output: output,
            recoverable: recoverable,
            payload: payload ?: [:]
        ))
    }
}
