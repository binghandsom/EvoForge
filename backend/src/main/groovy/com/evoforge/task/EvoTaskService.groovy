package com.evoforge.task

import com.evoforge.device.DeviceTaskEvent
import com.evoforge.device.DeviceTaskEventStore
import org.springframework.stereotype.Service

@Service
class EvoTaskService {
    private final EvoTaskStore taskStore
    private final DeviceTaskEventStore eventStore

    EvoTaskService(EvoTaskStore taskStore, DeviceTaskEventStore eventStore) {
        this.taskStore = taskStore
        this.eventStore = eventStore
    }

    List<EvoTask> listRecent(int limit = 50) {
        return taskStore.listRecent(Math.max(1, Math.min(limit, 200)))
    }

    EvoTask find(String taskId) {
        return taskStore.find(taskId)
    }

    Map<String, Object> snapshot(String taskId) {
        EvoTask task = find(taskId)
        List<DeviceTaskEvent> events = eventStore.listForTask(taskId)
        return [
            task  : task,
            events: events
        ] as Map<String, Object>
    }
}
