package com.evoforge.api

import com.evoforge.task.EvoTask
import com.evoforge.task.EvoTaskService
import org.springframework.web.bind.annotation.*

@RestController
@RequestMapping('/api/tasks')
class TaskController {
    private final EvoTaskService taskService

    TaskController(EvoTaskService taskService) {
        this.taskService = taskService
    }

    @GetMapping
    List<EvoTask> recentTasks(@RequestParam(name = 'limit', defaultValue = '50') int limit) {
        return taskService.listRecent(limit)
    }

    @GetMapping('{taskId}')
    EvoTask task(@PathVariable('taskId') String taskId) {
        return taskService.find(taskId)
    }

    @GetMapping('{taskId}/snapshot')
    Map<String, Object> snapshot(@PathVariable('taskId') String taskId) {
        return taskService.snapshot(taskId)
    }
}
