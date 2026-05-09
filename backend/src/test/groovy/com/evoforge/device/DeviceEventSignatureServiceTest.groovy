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

    @Test
    void signsClientResponsePayloadContainingInstantBackedViews() {
        EvoForgeProperties properties = new EvoForgeProperties()
        properties.deviceAgent.eventSigningSecret = 'unit-test-secret'
        DeviceEventSignatureService service = new DeviceEventSignatureService(properties)
        DeviceTaskSummary summary = new DeviceTaskSummary(
            taskId: 'task-2',
            userId: 'user-1',
            deviceId: 'pc-1',
            type: DeviceProtocol.TYPE_CLIENT_REQUEST,
            status: DeviceProtocol.STATUS_COMPLETED,
            level: 'info',
            message: 'ok',
            eventCount: 2,
            firstEventAt: Instant.parse('2026-05-09T05:24:00Z'),
            lastEventAt: Instant.parse('2026-05-09T05:24:02Z')
        )
        DeviceTaskEvent event = new DeviceTaskEvent(
            eventId: 'event-client-response',
            taskId: 'request-1',
            userId: 'user-1',
            deviceId: 'pc-1',
            type: DeviceProtocol.STATUS_CLIENT_RESPONSE,
            status: DeviceProtocol.STATUS_COMPLETED,
            level: 'info',
            message: 'Client request completed: device.tasks.list',
            recoverable: false,
            payload: [
                clientResponse: [
                    requestId: 'request-1',
                    method   : 'device.tasks.list',
                    ok       : true,
                    data     : [summary]
                ]
            ],
            createdAt: Instant.parse('2026-05-09T05:24:03Z')
        )

        service.sign(event)

        assertNotNull(event.payload.eventSignature)
        assertTrue(service.verify(event))
        String canonical = service.canonicalPayload(event)
        assertTrue(canonical.contains('"firstEventAt":"2026-05-09T05:24:00Z"'))
        assertTrue(canonical.contains('"lastEventAt":"2026-05-09T05:24:02Z"'))
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
