package com.evoforge.task

import com.evoforge.device.DeviceProtocol
import com.evoforge.device.DeviceTaskEvent
import com.evoforge.device.InMemoryDeviceTaskEventStore
import org.junit.jupiter.api.Test

import java.time.Instant

import static org.junit.jupiter.api.Assertions.*

class EvoTaskProjectionTest {

    @Test
    void projectsDeviceEventsIntoDurableTaskState() {
        InMemoryDeviceTaskEventStore store = new InMemoryDeviceTaskEventStore()

        store.append(new DeviceTaskEvent(
            eventId: 'event-1',
            taskId: 'task-1',
            userId: 'user-1',
            deviceId: 'pc-1',
            type: DeviceProtocol.STATUS_QUEUED,
            status: DeviceProtocol.STATUS_QUEUED,
            message: 'Command queued',
            payload: [
                commandType: DeviceProtocol.TYPE_CLIENT_REQUEST,
                text       : 'load dashboard'
            ],
            createdAt: Instant.parse('2026-05-09T00:00:00Z')
        ))
        store.append(new DeviceTaskEvent(
            eventId: 'event-2',
            taskId: 'task-1',
            userId: 'user-1',
            deviceId: 'pc-1',
            type: DeviceProtocol.STATUS_RUNNING,
            status: DeviceProtocol.STATUS_RUNNING,
            message: 'Execution started',
            createdAt: Instant.parse('2026-05-09T00:00:02Z')
        ))
        store.append(new DeviceTaskEvent(
            eventId: 'event-3',
            taskId: 'task-1',
            userId: 'user-1',
            deviceId: 'pc-1',
            type: DeviceProtocol.STATUS_CLIENT_RESPONSE,
            status: DeviceProtocol.STATUS_COMPLETED,
            message: 'Client request completed',
            payload: [clientResponse: [ok: true]],
            createdAt: Instant.parse('2026-05-09T00:00:03Z')
        ))

        EvoTask task = store.find('task-1')

        assertNotNull(task)
        assertEquals(DeviceProtocol.TYPE_CLIENT_REQUEST, task.taskType)
        assertEquals(DeviceProtocol.STATUS_COMPLETED, task.status)
        assertEquals('load dashboard', task.commandText)
        assertEquals(3, task.eventCount)
        assertEquals(Instant.parse('2026-05-09T00:00:00Z'), task.firstEventAt)
        assertEquals(Instant.parse('2026-05-09T00:00:03Z'), task.lastEventAt)
        assertEquals(Instant.parse('2026-05-09T00:00:02Z'), task.startedAt)
        assertEquals(Instant.parse('2026-05-09T00:00:03Z'), task.completedAt)
        assertEquals(true, task.resultPayload.clientResponse.ok)
    }

    @Test
    void listsRecentTasksByLatestEventTime() {
        InMemoryDeviceTaskEventStore store = new InMemoryDeviceTaskEventStore()
        store.append(new DeviceTaskEvent(
            eventId: 'event-a',
            taskId: 'task-a',
            type: DeviceProtocol.STATUS_QUEUED,
            status: DeviceProtocol.STATUS_QUEUED,
            createdAt: Instant.parse('2026-05-09T00:00:01Z')
        ))
        store.append(new DeviceTaskEvent(
            eventId: 'event-b',
            taskId: 'task-b',
            type: DeviceProtocol.STATUS_QUEUED,
            status: DeviceProtocol.STATUS_QUEUED,
            createdAt: Instant.parse('2026-05-09T00:00:02Z')
        ))

        assertEquals(['task-b', 'task-a'], store.listRecent(10).collect { it.taskId })
    }
}
