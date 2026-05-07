package com.evoforge.device

import com.evoforge.api.DeviceTaskApprovalRequest
import com.evoforge.core.EvoForgeProperties
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Service
import org.springframework.web.server.ResponseStatusException

@Service
class DeviceTaskApprovalService {
    private final DeviceTaskEventStore eventStore
    private final DeviceEventPublisher eventPublisher
    private final EvoForgeProperties properties

    DeviceTaskApprovalService(DeviceTaskEventStore eventStore,
                              DeviceEventPublisher eventPublisher,
                              EvoForgeProperties properties) {
        this.eventStore = eventStore
        this.eventPublisher = eventPublisher
        this.properties = properties
    }

    DeviceTaskApprovalResult approve(String taskId, DeviceTaskApprovalRequest request) {
        return applyDecision(taskId, DeviceProtocol.DECISION_APPROVE, actor(request), request?.note, true)
    }

    DeviceTaskApprovalResult reject(String taskId, DeviceTaskApprovalRequest request) {
        return applyDecision(taskId, DeviceProtocol.DECISION_REJECT, actor(request), request?.note, true)
    }

    DeviceTaskApprovalResult applyDecision(String taskId, String decision, String actor, String note, boolean dispatchApprovedCommand) {
        String normalizedDecision = (decision ?: '').toLowerCase()
        if (normalizedDecision == DeviceProtocol.DECISION_APPROVE) {
            return approveInternal(taskId, actor ?: 'mobile', note, dispatchApprovedCommand)
        }
        if (normalizedDecision == DeviceProtocol.DECISION_REJECT) {
            return rejectInternal(taskId, actor ?: 'mobile', note)
        }
        throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Unsupported approval decision: ${decision}".toString())
    }

    private DeviceTaskApprovalResult approveInternal(String taskId, String actor, String note, boolean dispatchApprovedCommand) {
        List<DeviceTaskEvent> events = loadPendingEvents(taskId)
        DeviceTaskEvent pending = events.reverse().find { it.status == DeviceProtocol.STATUS_NEEDS_APPROVAL }
        DeviceCommandMessage command = DeviceCommandPayload.toMessage(taskId, pending)
        command.commandId = UUID.randomUUID().toString()
        command.userId = command.userId ?: properties.deviceAgent.userId
        command.deviceId = command.deviceId ?: properties.deviceAgent.deviceId
        command.attributes = command.attributes ?: [:]
        command.attributes.approvalGranted = true
        command.attributes.approvedBy = actor
        if (note) {
            command.attributes.approvalNote = note
        }

        DeviceTaskEvent approved = eventPublisher.publish(new DeviceTaskEvent(
            taskId: taskId,
            userId: command.userId,
            deviceId: command.deviceId,
            type: DeviceProtocol.STATUS_APPROVED,
            status: DeviceProtocol.STATUS_APPROVED,
            level: 'info',
            message: "Command approved by ${actor}".toString(),
            recoverable: false,
            payload: [actor: actor, note: note]
        ))
        return new DeviceTaskApprovalResult(
            approved: true,
            event: approved,
            command: command
        )
    }

    private DeviceTaskApprovalResult rejectInternal(String taskId, String actor, String note) {
        List<DeviceTaskEvent> events = loadPendingEvents(taskId)
        DeviceTaskEvent pending = events.reverse().find { it.status == DeviceProtocol.STATUS_NEEDS_APPROVAL }
        DeviceTaskEvent rejected = eventPublisher.publish(new DeviceTaskEvent(
            taskId: taskId,
            userId: pending.userId ?: properties.deviceAgent.userId,
            deviceId: pending.deviceId ?: properties.deviceAgent.deviceId,
            type: DeviceProtocol.STATUS_REJECTED,
            status: DeviceProtocol.STATUS_REJECTED,
            level: 'warn',
            message: "Command rejected by ${actor}".toString(),
            recoverable: false,
            payload: [actor: actor, note: note]
        ))
        return new DeviceTaskApprovalResult(
            approved: false,
            event: rejected,
            command: null
        )
    }

    private List<DeviceTaskEvent> loadPendingEvents(String taskId) {
        List<DeviceTaskEvent> events = eventStore.listForTask(taskId)
        if (!events) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, 'Task not found')
        }
        List<DeviceTaskEvent> ordered = events.sort { it.createdAt }
        DeviceTaskEvent latestMeaningful = ordered.reverse().find { !isApprovalDecisionDispatchEvent(it) }
        if (latestMeaningful?.status != DeviceProtocol.STATUS_NEEDS_APPROVAL) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Task is not waiting for approval: ${latestMeaningful?.status}".toString())
        }
        return events
    }

    private static boolean isApprovalDecisionDispatchEvent(DeviceTaskEvent event) {
        return event.status == DeviceProtocol.STATUS_QUEUED &&
            event.payload?.commandType == DeviceProtocol.TYPE_APPROVAL_DECISION
    }

    private static String actor(DeviceTaskApprovalRequest request) {
        return request?.actor ?: 'mobile'
    }
}
