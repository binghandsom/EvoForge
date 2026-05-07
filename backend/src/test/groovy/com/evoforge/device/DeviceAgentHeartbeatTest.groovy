package com.evoforge.device

import com.evoforge.core.EvoForgeProperties
import org.junit.jupiter.api.Test

import static org.junit.jupiter.api.Assertions.*

class DeviceAgentHeartbeatTest {

    @Test
    void publishesSharedStatusSnapshot() {
        EvoForgeProperties properties = new EvoForgeProperties()
        properties.deviceAgent.enabled = true
        properties.deviceAgent.userId = 'user-1'
        properties.deviceAgent.deviceId = 'pc-1'
        properties.deviceAgent.commandSigningSecret = 'secret'
        CapturingPublisher publisher = new CapturingPublisher(properties)

        new DeviceAgentHeartbeat(
            publisher,
            properties,
            new DeviceStatusService(properties)
        ).heartbeat()

        DeviceTaskEvent event = publisher.events.first()
        assertEquals('heartbeat-pc-1', event.taskId)
        assertEquals('heartbeat', event.type)
        assertEquals('online', event.status)
        assertEquals('pc-1', event.payload.deviceId)
        assertEquals('user.user-1.device.pc-1.command', event.payload.commandRoutingKey)
        assertEquals(DeviceProtocol.capabilities(), event.payload.capabilities)
        assertEquals(true, (event.payload.commandSigning as Map).enabled)
    }

    private static class CapturingPublisher extends DeviceEventPublisher {
        List<DeviceTaskEvent> events = []

        CapturingPublisher(EvoForgeProperties properties) {
            super(null, null, properties, new DeviceEventSignatureService(properties))
        }

        @Override
        DeviceTaskEvent publish(DeviceTaskEvent event) {
            events << event
            return event
        }
    }
}
