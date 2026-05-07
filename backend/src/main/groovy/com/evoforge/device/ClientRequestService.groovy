package com.evoforge.device

import com.evoforge.agent.AgentConversationMemoryService
import org.springframework.stereotype.Service

@Service
class ClientRequestService {
    private final AgentConversationMemoryService conversationMemoryService
    private final DeviceTaskEventStore eventStore
    private final DeviceStatusService statusService

    ClientRequestService(AgentConversationMemoryService conversationMemoryService,
                         DeviceTaskEventStore eventStore,
                         DeviceStatusService statusService) {
        this.conversationMemoryService = conversationMemoryService
        this.eventStore = eventStore
        this.statusService = statusService
    }

    Map<String, Object> handle(DeviceCommandMessage command) {
        Map<String, Object> request = command.attributes?.request instanceof Map
            ? command.attributes.request as Map<String, Object>
            : command.attributes ?: [:]
        String method = (request.method ?: methodName(request.resource, request.action)).toString()
        Map params = request.params instanceof Map ? request.params as Map : [:]
        Object data = dispatch(method, params)
        return [
            requestId: request.requestId ?: command.taskId,
            method   : method,
            ok       : true,
            data     : data
        ] as Map<String, Object>
    }

    private Object dispatch(String method, Map params) {
        switch (method) {
            case 'agent.conversations.list':
                int limit = intParam(params.limit, 50, 1, 200)
                return conversationMemoryService.threadViews(conversationMemoryService.listThreads(limit))
            case 'agent.conversations.create':
                def thread = conversationMemoryService.createThread(
                    params.threadId?.toString(),
                    params.title?.toString(),
                    params.metadata instanceof Map ? params.metadata as Map<String, Object> : [:]
                )
                return conversationMemoryService.threadView(thread)
            case 'agent.conversations.turns':
                String threadId = required(params.threadId, 'threadId')
                int limit = intParam(params.limit, 100, 1, 200)
                return conversationMemoryService.toView(conversationMemoryService.recent(threadId, limit))
            case 'device.tasks.list':
                int limit = intParam(params.limit, 50, 1, 200)
                return eventStore.listRecentTasks(limit)
            case 'device.tasks.events':
                return eventStore.listForTask(required(params.taskId, 'taskId'))
            case 'device.status.get':
                return statusService.status()
            default:
                throw new IllegalArgumentException("Unsupported client request method: ${method}".toString())
        }
    }

    private static String methodName(Object resource, Object action) {
        String resourceText = resource?.toString()?.trim()
        String actionText = action?.toString()?.trim()
        if (!resourceText || !actionText) {
            return ''
        }
        return "${resourceText}.${actionText}".toString()
    }

    private static String required(Object value, String field) {
        String text = value?.toString()?.trim()
        if (!text) {
            throw new IllegalArgumentException("${field} is required")
        }
        return text
    }

    private static int intParam(Object value, int defaultValue, int min, int max) {
        int parsed = value instanceof Number ? value.intValue() : value?.toString()?.isInteger() ? value.toString().toInteger() : defaultValue
        return Math.max(min, Math.min(max, parsed))
    }
}
