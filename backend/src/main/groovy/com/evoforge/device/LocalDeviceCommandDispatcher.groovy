package com.evoforge.device

import com.evoforge.core.EvoForgeProperties
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.stereotype.Service

import java.time.Instant

@Service
@ConditionalOnProperty(prefix = 'evoforge.deviceAgent', name = 'enabled', havingValue = 'false', matchIfMissing = true)
class LocalDeviceCommandDispatcher implements DeviceCommandDispatcher {
    private final DeviceAgentExecutor executor
    private final DeviceEventPublisher eventPublisher
    private final DeviceCommandSignatureService signatureService
    private final EvoForgeProperties properties

    LocalDeviceCommandDispatcher(DeviceAgentExecutor executor,
                                 DeviceEventPublisher eventPublisher,
                                 DeviceCommandSignatureService signatureService,
                                 EvoForgeProperties properties) {
        this.executor = executor
        this.eventPublisher = eventPublisher
        this.signatureService = signatureService
        this.properties = properties
    }

    @Override
    DeviceTaskEvent dispatch(DeviceCommandMessage command) {
        command.commandId = command.commandId ?: UUID.randomUUID().toString()
        command.taskId = command.taskId ?: UUID.randomUUID().toString()
        command.userId = command.userId ?: properties.deviceAgent.userId
        command.deviceId = command.deviceId ?: properties.deviceAgent.deviceId
        command.type = command.type ?: DeviceProtocol.TYPE_NATURAL_LANGUAGE_TASK
        command.attributes = command.attributes ?: [:]
        command.createdAt = command.createdAt ?: Instant.now().toString()
        signatureService.sign(command)
        DeviceTaskEvent queued = eventPublisher.publish(new DeviceTaskEvent(
            taskId: command.taskId,
            userId: command.userId,
            deviceId: command.deviceId,
            type: DeviceProtocol.STATUS_QUEUED,
            status: DeviceProtocol.STATUS_QUEUED,
            level: 'info',
            message: 'Command executed locally without RabbitMQ',
            payload: DeviceCommandPayload.from(command)
        ))
        executor.handle(command)
        return queued
    }
}
