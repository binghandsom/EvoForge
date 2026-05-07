package com.evoforge.device

import com.evoforge.core.EvoForgeProperties
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component

@Component
@ConditionalOnProperty(prefix = 'evoforge.deviceAgent', name = 'enabled', havingValue = 'true')
class DeviceAgentHeartbeat {
    private final DeviceEventPublisher eventPublisher
    private final EvoForgeProperties properties
    private final DeviceStatusService statusService

    DeviceAgentHeartbeat(DeviceEventPublisher eventPublisher,
                         EvoForgeProperties properties,
                         DeviceStatusService statusService) {
        this.eventPublisher = eventPublisher
        this.properties = properties
        this.statusService = statusService
    }

    @Scheduled(fixedDelayString = '${evoforge.deviceAgent.heartbeatSeconds:30}000')
    void heartbeat() {
        eventPublisher.publish(new DeviceTaskEvent(
            taskId: "heartbeat-${properties.deviceAgent.deviceId}".toString(),
            userId: properties.deviceAgent.userId,
            deviceId: properties.deviceAgent.deviceId,
            type: 'heartbeat',
            status: 'online',
            level: 'info',
            message: 'Device agent is online',
            payload: statusService.status()
        ))
    }
}
