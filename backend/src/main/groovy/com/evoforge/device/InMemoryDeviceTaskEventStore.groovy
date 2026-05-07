package com.evoforge.device

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.stereotype.Component

import java.time.Instant
import java.util.concurrent.CopyOnWriteArrayList

@Component
@ConditionalOnProperty(prefix = 'evoforge.skills', name = 'storageBackend', havingValue = 'file')
class InMemoryDeviceTaskEventStore implements DeviceTaskEventStore {
    private final List<DeviceTaskEvent> events = new CopyOnWriteArrayList<>()

    @Override
    DeviceTaskEvent append(DeviceTaskEvent event) {
        if (events.any { it.eventId == event.eventId }) {
            return event
        }
        events << event
        return event
    }

    @Override
    List<DeviceTaskEvent> listForTask(String taskId) {
        return events.findAll { it.taskId == taskId }.sort { it.createdAt }
    }

    @Override
    List<DeviceTaskSummary> listRecentTasks(int limit) {
        int safeLimit = Math.max(0, limit)
        return events
            .findAll { it.taskId }
            .groupBy { it.taskId }
            .collect { String taskId, List<DeviceTaskEvent> taskEvents ->
                List<DeviceTaskEvent> ordered = taskEvents.sort { it.createdAt }
                DeviceTaskEvent first = ordered.first()
                DeviceTaskEvent latest = ordered.last()
                return new DeviceTaskSummary(
                    taskId: taskId,
                    userId: latest.userId,
                    deviceId: latest.deviceId,
                    type: textValue(first.payload?.commandType) ?: textValue(latest.payload?.commandType) ?: latest.type,
                    status: latest.status,
                    level: latest.level ?: 'info',
                    message: latest.message,
                    commandText: textValue(first.payload?.text) ?: textValue(latest.payload?.text),
                    recoverable: latest.recoverable,
                    eventCount: ordered.size(),
                    firstEventAt: first.createdAt,
                    lastEventAt: latest.createdAt
                )
            }
            .sort { DeviceTaskSummary a, DeviceTaskSummary b ->
                (b.lastEventAt ?: Instant.EPOCH) <=> (a.lastEventAt ?: Instant.EPOCH)
            }
            .take(safeLimit)
    }

    private static String textValue(Object value) {
        return value == null ? null : value.toString()
    }
}
