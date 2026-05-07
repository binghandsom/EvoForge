package com.evoforge.device

import com.evoforge.core.EvoForgeProperties
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.stereotype.Service

@Service
@ConditionalOnProperty(prefix = 'evoforge.deviceAgent', name = 'enabled', havingValue = 'false', matchIfMissing = true)
class NoopDeviceEventPublisher extends DeviceEventPublisher {
    NoopDeviceEventPublisher(DeviceTaskEventStore eventStore,
                             EvoForgeProperties properties,
                             DeviceEventSignatureService signatureService) {
        super(null, eventStore, properties, signatureService)
    }

    @Override
    DeviceTaskEvent publish(DeviceTaskEvent event) {
        event.userId = event.userId ?: properties.deviceAgent.userId
        event.deviceId = event.deviceId ?: properties.deviceAgent.deviceId
        signatureService.sign(event)
        return eventStore.append(event)
    }
}
