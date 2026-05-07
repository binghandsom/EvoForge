package com.evoforge.device

import org.springframework.amqp.rabbit.annotation.RabbitListener
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.stereotype.Component

@Component
@ConditionalOnProperty(prefix = 'evoforge.deviceAgent', name = 'enabled', havingValue = 'true')
class DeviceEventListener {
    private final DeviceTaskEventStore eventStore
    private final DeviceEventSignatureService signatureService

    DeviceEventListener(DeviceTaskEventStore eventStore,
                        DeviceEventSignatureService signatureService) {
        this.eventStore = eventStore
        this.signatureService = signatureService
    }

    @RabbitListener(queues = '#{@deviceEventQueue.name}', containerFactory = 'deviceAgentListenerContainerFactory')
    void onEvent(DeviceTaskEvent event) {
        if (!signatureService.verify(event)) {
            return
        }
        eventStore.append(event)
    }
}
