package com.evoforge.api

import com.evoforge.device.DeviceCommandDispatcher
import com.evoforge.device.DeviceCommandMessage
import com.evoforge.device.DeviceStatusService
import com.evoforge.device.DeviceTaskApprovalService
import com.evoforge.device.DeviceTaskApprovalResult
import com.evoforge.device.DeviceTaskEvent
import com.evoforge.device.DeviceTaskEventStore
import com.evoforge.device.DeviceTaskSummary
import org.springframework.web.bind.annotation.*

@RestController
@RequestMapping('/api/device')
class DeviceController {
    private final DeviceTaskEventStore eventStore
    private final DeviceCommandDispatcher commandDispatcher
    private final DeviceTaskApprovalService approvalService
    private final DeviceStatusService statusService

    DeviceController(DeviceTaskEventStore eventStore,
                     DeviceCommandDispatcher commandDispatcher,
                     DeviceTaskApprovalService approvalService,
                     DeviceStatusService statusService) {
        this.eventStore = eventStore
        this.commandDispatcher = commandDispatcher
        this.approvalService = approvalService
        this.statusService = statusService
    }

    @GetMapping('status')
    Map<String, Object> status() {
        return statusService.status()
    }

    @GetMapping('tasks/{taskId}/events')
    List<DeviceTaskEvent> taskEvents(@PathVariable('taskId') String taskId) {
        return eventStore.listForTask(taskId)
    }

    @GetMapping('tasks')
    List<DeviceTaskSummary> recentTasks(@RequestParam(name = 'limit', defaultValue = '50') int limit) {
        return eventStore.listRecentTasks(Math.max(1, Math.min(limit, 200)))
    }

    @PostMapping('commands')
    DeviceTaskEvent dispatchCommand(@RequestBody DeviceCommandMessage command) {
        return commandDispatcher.dispatch(command)
    }

    @PostMapping('tasks/{taskId}/approve')
    DeviceTaskEvent approveTask(@PathVariable('taskId') String taskId,
                                @RequestBody(required = false) DeviceTaskApprovalRequest request) {
        DeviceTaskApprovalResult result = approvalService.approve(taskId, request ?: new DeviceTaskApprovalRequest())
        commandDispatcher.dispatch(result.command)
        return result.event
    }

    @PostMapping('tasks/{taskId}/reject')
    DeviceTaskEvent rejectTask(@PathVariable('taskId') String taskId,
                               @RequestBody(required = false) DeviceTaskApprovalRequest request) {
        return approvalService.reject(taskId, request ?: new DeviceTaskApprovalRequest()).event
    }

}
