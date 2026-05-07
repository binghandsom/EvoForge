package com.evoforge.device

import com.evoforge.core.EvoForgeProperties
import org.junit.jupiter.api.Test

import java.time.Instant

import static org.junit.jupiter.api.Assertions.*

class DeviceEventSignatureServiceTest {

    @Test
    void signsEventAndExcludesExistingSignatureFromPayload() {
        EvoForgeProperties properties = new EvoForgeProperties()
        properties.deviceAgent.eventSigningSecret = 'unit-test-secret'
        DeviceEventSignatureService service = new DeviceEventSignatureService(properties)
        DeviceTaskEvent event = event()

        service.sign(event)
        String signature = event.payload.eventSignature
        event.payload.eventSignature = 'transport-copy'

        assertEquals(signature, service.signatureFor(event))
        assertFalse(service.verify(event))
        event.payload.eventSignature = signature
        assertTrue(service.verify(event))
    }

    @Test
    void disabledSigningLeavesEventUntouched() {
        DeviceEventSignatureService service = new DeviceEventSignatureService(new EvoForgeProperties())
        DeviceTaskEvent event = event()

        service.sign(event)

        assertFalse(event.payload.containsKey('eventSignature'))
    }

    private static DeviceTaskEvent event() {
        return new DeviceTaskEvent(
            eventId: 'event-1',
            taskId: 'task-1',
            userId: 'user-1',
            deviceId: 'pc-1',
            type: DeviceProtocol.STATUS_COMPLETED,
            status: DeviceProtocol.STATUS_COMPLETED,
            level: 'info',
            message: 'done',
            output: 'ok',
            recoverable: false,
            payload: [nested: [b: 2, a: 1], priority: 'high'],
            createdAt: Instant.parse('2026-05-07T00:00:00Z')
        )
    }
}
