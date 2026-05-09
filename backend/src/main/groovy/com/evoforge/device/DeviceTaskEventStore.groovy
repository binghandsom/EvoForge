package com.evoforge.device

interface DeviceTaskEventStore {
    DeviceTaskEvent append(DeviceTaskEvent event)
    List<DeviceTaskEvent> listForTask(String taskId)
    DeviceTaskEventPage listForTaskPage(String taskId, int limit, String beforeCursor, String afterCursor)
    List<DeviceTaskSummary> listRecentTasks(int limit)
}
