package com.evoforge.device

import com.evoforge.core.EvoForgeProperties
import org.springframework.amqp.rabbit.core.RabbitTemplate
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.stereotype.Service

@Service
@ConditionalOnProperty(prefix = 'evoforge.deviceAgent', name = 'enabled', havingValue = 'true')
class DeviceEventPublisher {
    protected final RabbitTemplate rabbitTemplate
    protected final DeviceTaskEventStore eventStore
    protected final EvoForgeProperties properties
    protected final DeviceEventSignatureService signatureService

    DeviceEventPublisher(RabbitTemplate rabbitTemplate,
                         DeviceTaskEventStore eventStore,
                         EvoForgeProperties properties,
                         DeviceEventSignatureService signatureService) {
        this.rabbitTemplate = rabbitTemplate
        this.eventStore = eventStore
        this.properties = properties
        this.signatureService = signatureService
    }

    DeviceTaskEvent publish(DeviceTaskEvent event) {
        event.userId = event.userId ?: properties.deviceAgent.userId
        event.deviceId = event.deviceId ?: properties.deviceAgent.deviceId
        signatureService.sign(event)
        eventStore.append(event)
        rabbitTemplate.convertAndSend(properties.deviceAgent.eventExchange, eventRoutingKey(event), event)
        return event
    }

    private String eventRoutingKey(DeviceTaskEvent event) {
        return (properties.deviceAgent.eventRoutingKey ?: "user.${event.userId}.device.${event.deviceId}.event").toString()
    }
}
