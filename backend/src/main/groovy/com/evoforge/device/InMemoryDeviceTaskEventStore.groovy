package com.evoforge.device

import com.evoforge.task.EvoTask
import com.evoforge.task.EvoTaskProjector
import com.evoforge.task.EvoTaskStore
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.stereotype.Component

import java.time.Instant
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.ConcurrentHashMap

@Component
@ConditionalOnProperty(prefix = 'evoforge.skills', name = 'storageBackend', havingValue = 'file')
class InMemoryDeviceTaskEventStore implements DeviceTaskEventStore, EvoTaskStore {
    private final List<DeviceTaskEvent> events = new CopyOnWriteArrayList<>()
    private final Map<String, EvoTask> tasks = new ConcurrentHashMap<>()

    @Override
    DeviceTaskEvent append(DeviceTaskEvent event) {
        if (events.any { it.eventId == event.eventId }) {
            return event
        }
        events << event
        if (event.taskId) {
            tasks.compute(event.taskId) { String ignored, EvoTask existing ->
                EvoTaskProjector.apply(existing, event)
            }
        }
        return event
    }

    @Override
    List<DeviceTaskEvent> listForTask(String taskId) {
        return sortedForTask(taskId)
    }

    @Override
    DeviceTaskEventPage listForTaskPage(String taskId, int limit, String beforeCursor, String afterCursor) {
        int safeLimit = Math.max(1, Math.min(limit, 200))
        DeviceTaskEventPage.Cursor before = DeviceTaskEventPage.parseCursor(beforeCursor)
        DeviceTaskEventPage.Cursor after = DeviceTaskEventPage.parseCursor(afterCursor)
        List<DeviceTaskEvent> ordered = sortedForTask(taskId)
        List<DeviceTaskEvent> filtered = ordered
        boolean hasMoreBefore = false
        boolean hasMoreAfter = false

        if (after) {
            filtered = ordered.findAll { event ->
                DeviceTaskEventPage.compareEventPosition(event.createdAt, event.eventId, after.createdAt, after.eventId) > 0
            }
            hasMoreAfter = filtered.size() > safeLimit
            return DeviceTaskEventPage.fromItems(filtered.take(safeLimit), safeLimit, false, hasMoreAfter)
        }
        if (before) {
            filtered = ordered.findAll { event ->
                DeviceTaskEventPage.compareEventPosition(event.createdAt, event.eventId, before.createdAt, before.eventId) < 0
            }
            hasMoreBefore = filtered.size() > safeLimit
            return DeviceTaskEventPage.fromItems(filtered.takeRight(safeLimit), safeLimit, hasMoreBefore, false)
        }

        hasMoreBefore = ordered.size() > safeLimit
        return DeviceTaskEventPage.fromItems(ordered.takeRight(safeLimit), safeLimit, hasMoreBefore, false)
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

    @Override
    EvoTask find(String taskId) {
        return tasks[taskId]
    }

    @Override
    List<EvoTask> listRecent(int limit) {
        int safeLimit = Math.max(0, Math.min(limit, 200))
        return tasks.values()
            .sort { EvoTask a, EvoTask b ->
                (b.lastEventAt ?: Instant.EPOCH) <=> (a.lastEventAt ?: Instant.EPOCH)
            }
            .take(safeLimit)
    }

    private static String textValue(Object value) {
        return value == null ? null : value.toString()
    }

    private List<DeviceTaskEvent> sortedForTask(String taskId) {
        return events.findAll { it.taskId == taskId }.sort { a, b ->
            DeviceTaskEventPage.compareEventPosition(a.createdAt, a.eventId, b.createdAt, b.eventId)
        }
    }
}
