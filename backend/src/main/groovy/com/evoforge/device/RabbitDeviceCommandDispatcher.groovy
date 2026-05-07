package com.evoforge.device

import com.evoforge.core.EvoForgeProperties
import org.springframework.amqp.rabbit.core.RabbitTemplate
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.stereotype.Service

import java.time.Instant

@Service
@ConditionalOnProperty(prefix = 'evoforge.deviceAgent', name = 'enabled', havingValue = 'true')
class RabbitDeviceCommandDispatcher implements DeviceCommandDispatcher {
    private final RabbitTemplate rabbitTemplate
    private final DeviceEventPublisher eventPublisher
    private final DeviceCommandSignatureService signatureService
    private final EvoForgeProperties properties

    RabbitDeviceCommandDispatcher(RabbitTemplate rabbitTemplate,
                                  DeviceEventPublisher eventPublisher,
                                  DeviceCommandSignatureService signatureService,
                                  EvoForgeProperties properties) {
        this.rabbitTemplate = rabbitTemplate
        this.eventPublisher = eventPublisher
        this.signatureService = signatureService
        this.properties = properties
    }

    @Override
    DeviceTaskEvent dispatch(DeviceCommandMessage command) {
        DeviceCommandMessage normalized = normalize(command)
        signatureService.sign(normalized)
        rabbitTemplate.convertAndSend(properties.deviceAgent.commandExchange, commandRoutingKey(normalized), normalized)
        return eventPublisher.publish(new DeviceTaskEvent(
            taskId: normalized.taskId,
            userId: normalized.userId,
            deviceId: normalized.deviceId,
            type: DeviceProtocol.STATUS_QUEUED,
            status: DeviceProtocol.STATUS_QUEUED,
            level: 'info',
            message: 'Command queued for device agent',
            payload: DeviceCommandPayload.from(normalized)
        ))
    }

    private DeviceCommandMessage normalize(DeviceCommandMessage command) {
        command.commandId = command.commandId ?: UUID.randomUUID().toString()
        command.taskId = command.taskId ?: UUID.randomUUID().toString()
        command.userId = command.userId ?: properties.deviceAgent.userId
        command.deviceId = command.deviceId ?: properties.deviceAgent.deviceId
        command.type = command.type ?: DeviceProtocol.TYPE_NATURAL_LANGUAGE_TASK
        command.attributes = command.attributes ?: [:]
        command.createdAt = command.createdAt ?: Instant.now().toString()
        return command
    }

    private String commandRoutingKey(DeviceCommandMessage command) {
        return (properties.deviceAgent.commandRoutingKey ?: "user.${command.userId}.device.${command.deviceId}.command").toString()
    }
}
