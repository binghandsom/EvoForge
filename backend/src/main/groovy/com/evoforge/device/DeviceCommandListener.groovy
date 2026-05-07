package com.evoforge.device

import org.springframework.amqp.rabbit.annotation.RabbitListener
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.stereotype.Component

@Component
@ConditionalOnProperty(prefix = 'evoforge.deviceAgent', name = 'enabled', havingValue = 'true')
class DeviceCommandListener {
    private final DeviceAgentExecutor executor
    private final DeviceEventPublisher eventPublisher
    private final DeviceCommandSignatureService signatureService

    DeviceCommandListener(DeviceAgentExecutor executor,
                          DeviceEventPublisher eventPublisher,
                          DeviceCommandSignatureService signatureService) {
        this.executor = executor
        this.eventPublisher = eventPublisher
        this.signatureService = signatureService
    }

    @RabbitListener(queues = '#{@deviceCommandQueue.name}', containerFactory = 'deviceAgentListenerContainerFactory')
    void onCommand(DeviceCommandMessage command) {
        handle(command, false)
    }

    @RabbitListener(queues = '#{@deviceRequestQueue.name}', containerFactory = 'deviceAgentListenerContainerFactory')
    void onClientRequest(DeviceCommandMessage command) {
        handle(command, true)
    }

    private void handle(DeviceCommandMessage command, boolean requestOnly) {
        DeviceCommandSignatureVerification verification = signatureService.verifyDetailed(command)
        if (!verification.valid) {
            publishRejectedSignature(command, verification.reason)
            return
        }
        if (requestOnly && command?.type != DeviceProtocol.TYPE_CLIENT_REQUEST) {
            publishRejectedSignature(command, 'Request queue only accepts client_request commands')
            return
        }
        executor.handle(command)
    }

    private DeviceTaskEvent publishRejectedSignature(DeviceCommandMessage command, String reason) {
        return eventPublisher.publish(new DeviceTaskEvent(
            taskId: command?.taskId ?: UUID.randomUUID().toString(),
            userId: command?.userId,
            deviceId: command?.deviceId,
            type: DeviceProtocol.STATUS_FAILED,
            status: DeviceProtocol.STATUS_FAILED,
            level: 'error',
            message: reason ?: 'Invalid command signature',
            recoverable: false,
            payload: [commandType: command?.type]
        ))
    }
}
