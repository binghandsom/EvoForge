package com.evoforge.device

import com.evoforge.core.EvoForgeProperties
import org.junit.jupiter.api.Test

import static org.junit.jupiter.api.Assertions.*

class DeviceStatusServiceTest {

    @Test
    void buildsSharedDeviceStatusSnapshot() {
        EvoForgeProperties properties = new EvoForgeProperties()
        properties.deviceAgent.enabled = true
        properties.deviceAgent.userId = 'user-1'
        properties.deviceAgent.deviceId = 'pc-1'
        properties.deviceAgent.commandSigningSecret = 'secret'
        properties.deviceAgent.eventSigningSecret = 'event-secret'
        properties.deviceAgent.commandSignatureTtlSeconds = 120
        properties.skills.storageBackend = 'postgres'
        properties.codexTask.defaultWorkspace = 'evoforge'
        properties.codexTask.workspaces = [
            evoforge: '/projects/evoforge',
            app     : '/projects/app'
        ]

        Map<String, Object> status = new DeviceStatusService(properties).status()

        assertEquals(true, status.enabled)
        assertEquals('user-1', status.userId)
        assertEquals('pc-1', status.deviceId)
        assertEquals('evoforge.device.pc-1.commands', status.commandQueue)
        assertEquals('user.user-1.device.pc-1.command', status.commandRoutingKey)
        assertEquals(DeviceProtocol.commandTypes(), status.commandTypes)
        assertEquals(DeviceProtocol.capabilities(), status.capabilities)
        assertEquals(true, (status.commandSigning as Map).enabled)
        assertEquals(120, (status.commandSigning as Map).ttlSeconds)
        assertEquals('postgres', (status.commandSigning as Map).replayStore)
        assertEquals(true, (status.commandSigning as Map).persistentReplayProtection)
        assertEquals(true, (status.eventSigning as Map).enabled)
        assertEquals('evoforge', (status.codexTask as Map).defaultWorkspace)
        assertEquals([
            [key: 'app', path: '/projects/app'],
            [key: 'evoforge', path: '/projects/evoforge']
        ], (status.codexTask as Map).workspaces)
    }
}
