package com.evoforge.device

import com.evoforge.api.AgentRequest
import com.evoforge.agent.ProjectLearningService
import com.evoforge.codex.CodexQuestionBridgeService
import com.evoforge.core.AgentService
import com.evoforge.core.EvoForgeProperties
import com.evoforge.tester.EvoForgeTesterService
import com.evoforge.tester.TesterRunResult
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service

@Service
class DeviceAgentExecutor {
    private static final Logger log = LoggerFactory.getLogger(DeviceAgentExecutor)

    private final AgentService agentService
    private final CodexTaskExecutor codexTaskExecutor
    private final ClientRequestService clientRequestService
    private final DeviceTaskApprovalService approvalService
    private final DeviceEventPublisher eventPublisher
    private final ProjectLearningService projectLearningService
    private final CodexQuestionBridgeService questionBridgeService
    private final EvoForgeTesterService testerService
    private final EvoForgeProperties properties

    DeviceAgentExecutor(AgentService agentService,
                        CodexTaskExecutor codexTaskExecutor,
                        ClientRequestService clientRequestService,
                        DeviceTaskApprovalService approvalService,
                        DeviceEventPublisher eventPublisher,
                        ProjectLearningService projectLearningService,
                        CodexQuestionBridgeService questionBridgeService,
                        EvoForgeTesterService testerService,
                        EvoForgeProperties properties) {
        this.agentService = agentService
        this.codexTaskExecutor = codexTaskExecutor
        this.clientRequestService = clientRequestService
        this.approvalService = approvalService
        this.eventPublisher = eventPublisher
        this.projectLearningService = projectLearningService
        this.questionBridgeService = questionBridgeService
        this.testerService = testerService
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
            if (normalized.type == DeviceProtocol.TYPE_CLIENT_REQUEST) {
                runClientRequest(normalized)
                return
            }
            if (normalized.type == DeviceProtocol.TYPE_CODEX_TASK) {
                runCodexTask(normalized)
                return
            }
            if (normalized.type == DeviceProtocol.TYPE_TESTER_TASK) {
                runTesterTask(normalized)
                return
            }
            if (normalized.type == DeviceProtocol.TYPE_HUMAN_RESPONSE) {
                runHumanResponse(normalized)
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
                failurePayload(normalized, ex),
                true
            )
        }
    }

    private void runCodexTask(DeviceCommandMessage command) {
        CodexTaskResult result = codexTaskExecutor.execute(command)
        Map<String, Object> learning = projectLearningService.recordCodexTask(command, result)
        TesterRunResult testerResult = null
        Map testerConfig = EvoForgeTesterService.testerConfig(command)
        if (testerConfig.enabled == true && result.status == DeviceProtocol.STATUS_COMPLETED) {
            publish(command, DeviceProtocol.STATUS_AGENT_PROGRESS, DeviceProtocol.STATUS_RUNNING, 'info', 'EvoForge tester lane started', null, [testerProgress: [phase: 'started']], false)
            testerResult = testerService.runAfterCodex(command, result) { Map progress ->
                publish(
                    command,
                    DeviceProtocol.STATUS_AGENT_PROGRESS,
                    DeviceProtocol.STATUS_RUNNING,
                    progress.phase == 'command_failed' ? 'error' : 'info',
                    progress.message?.toString() ?: 'EvoForge tester progress',
                    progress.output?.toString(),
                    [testerProgress: progress],
                    false
                )
            }
        }
        String finalStatus = testerResult?.status == DeviceProtocol.STATUS_FAILED ? DeviceProtocol.STATUS_FAILED : result.status
        String finalMessage = testerResult?.status == DeviceProtocol.STATUS_FAILED
            ? 'Codex task completed; EvoForge tester found failures'
            : testerResult?.status == DeviceProtocol.STATUS_COMPLETED
                ? 'Codex task completed; EvoForge tester passed'
                : result.message
        String level = finalStatus == DeviceProtocol.STATUS_FAILED ? 'error' : finalStatus == DeviceProtocol.STATUS_NEEDS_APPROVAL ? 'warn' : 'info'
        publish(
            command,
            finalStatus,
            finalStatus,
            level,
            finalMessage,
            result.output,
            [
                commandType    : command.type,
                projectKey     : command.attributes?.projectKey,
                projectLearning: learning?.enabled == true ? learning : null,
                testerRun      : testerResult?.toMap(true)
            ].findAll { it.value != null } as Map<String, Object>,
            testerResult?.recoverable ?: result.recoverable
        )
    }

    private void runTesterTask(DeviceCommandMessage command) {
        TesterRunResult result = testerService.run(command) { Map progress ->
            publish(
                command,
                DeviceProtocol.STATUS_AGENT_PROGRESS,
                DeviceProtocol.STATUS_RUNNING,
                progress.phase == 'command_failed' ? 'error' : 'info',
                progress.message?.toString() ?: 'EvoForge tester progress',
                progress.output?.toString(),
                [testerProgress: progress],
                false
            )
        }
        publish(
            command,
            result.status,
            result.status,
            result.status == DeviceProtocol.STATUS_FAILED ? 'error' : 'info',
            result.message,
            result.output,
            [
                commandType: command.type,
                projectKey : command.attributes?.projectKey,
                testerRun  : result.toMap(true)
            ].findAll { it.value != null } as Map<String, Object>,
            result.recoverable
        )
    }

    private void runClientRequest(DeviceCommandMessage command) {
        Map<String, Object> response = clientRequestService.handle(command)
        publish(
            command,
            DeviceProtocol.STATUS_CLIENT_RESPONSE,
            DeviceProtocol.STATUS_COMPLETED,
            'info',
            "Client request completed: ${response.method}".toString(),
            null,
            [clientResponse: response],
            false
        )
    }

    private void runHumanResponse(DeviceCommandMessage command) {
        Map<String, Object> response = questionBridgeService.answer(command)
        publish(
            command,
            DeviceProtocol.STATUS_INPUT_RECEIVED,
            DeviceProtocol.STATUS_COMPLETED,
            response.delivered == true ? 'info' : 'warn',
            response.delivered == true ? 'Human response delivered to waiting question' : 'Human response recorded but no waiting question was found',
            null,
            [
                commandType: command.type,
                codexQuestionAnswer: response
            ] as Map<String, Object>,
            response.delivered != true
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
        return command.requiresApproval ||
            (command.type == DeviceProtocol.TYPE_CODEX_TASK && properties.codexTask.requiresApproval) ||
            (command.type == DeviceProtocol.TYPE_TESTER_TASK && properties.tester.requiresApproval)
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

    private static Map<String, Object> failurePayload(DeviceCommandMessage command, Exception ex) {
        Map<String, Object> payload = [errorType: ex.class.name] as Map<String, Object>
        if (command.type == DeviceProtocol.TYPE_CLIENT_REQUEST) {
            Map request = command.attributes?.request instanceof Map ? command.attributes.request as Map : command.attributes ?: [:]
            payload.clientResponse = [
                requestId: request.requestId ?: command.taskId,
                method   : request.method ?: '',
                ok       : false,
                error    : ex.message ?: ex.class.simpleName
            ]
        }
        return payload
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
