package com.evoforge.device

import org.junit.jupiter.api.Test

import java.time.Instant

import static org.junit.jupiter.api.Assertions.*

class DeviceTaskEventStorePaginationTest {

    @Test
    void pagesTaskEventsByCursorWithoutOffsetDrift() {
        InMemoryDeviceTaskEventStore store = new InMemoryDeviceTaskEventStore()
        (1..5).each { index ->
            store.append(new DeviceTaskEvent(
                eventId: "event-${index}".toString(),
                taskId: 'task-1',
                status: 'running',
                type: 'agent_progress',
                message: "event ${index}".toString(),
                createdAt: Instant.parse("2026-05-08T00:00:0${index}Z")
            ))
        }

        DeviceTaskEventPage latest = store.listForTaskPage('task-1', 2, '', '')

        assertEquals(['event-4', 'event-5'], latest.items.collect { it.eventId })
        assertTrue(latest.hasMoreBefore)
        assertFalse(latest.hasMoreAfter)

        store.append(new DeviceTaskEvent(
            eventId: 'event-6',
            taskId: 'task-1',
            status: 'running',
            type: 'agent_progress',
            message: 'event 6',
            createdAt: Instant.parse('2026-05-08T00:00:06Z')
        ))

        DeviceTaskEventPage older = store.listForTaskPage('task-1', 2, latest.beforeCursor, '')

        assertEquals(['event-2', 'event-3'], older.items.collect { it.eventId })
        assertTrue(older.hasMoreBefore)
    }
}
