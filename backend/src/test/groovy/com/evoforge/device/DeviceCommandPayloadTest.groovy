package com.evoforge.device

import org.junit.jupiter.api.Test

import static org.junit.jupiter.api.Assertions.*

class DeviceCommandPayloadTest {

    @Test
    void excludesTransportSignatureFromStoredPayload() {
        DeviceCommandMessage command = new DeviceCommandMessage(
            commandId: 'command-1',
            taskId: 'task-1',
            type: DeviceProtocol.TYPE_NATURAL_LANGUAGE_TASK,
            text: 'hello',
            attributes: [
                signature: 'secret-hmac',
                priority : 'high',
                projectKey: 'evoforge'
            ]
        )

        Map<String, Object> payload = DeviceCommandPayload.from(command)

        assertEquals('command-1', payload.commandId)
        assertEquals('evoforge', payload.projectKey)
        assertEquals('high', (payload.attributes as Map).priority)
        assertEquals('evoforge', (payload.attributes as Map).projectKey)
        assertFalse((payload.attributes as Map).containsKey('signature'))
    }
}
