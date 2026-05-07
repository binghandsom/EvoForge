package com.evoforge.device

interface DeviceTaskEventStore {
    DeviceTaskEvent append(DeviceTaskEvent event)
    List<DeviceTaskEvent> listForTask(String taskId)
    List<DeviceTaskSummary> listRecentTasks(int limit)
}
