package com.evoforge.task

import com.evoforge.device.DeviceProtocol
import com.evoforge.device.DeviceTaskEvent

import java.time.Instant

final class EvoTaskProjector {
    private EvoTaskProjector() {
    }

    static EvoTask apply(EvoTask existing, DeviceTaskEvent event) {
        EvoTask task = existing ?: new EvoTask(
            taskId: event.taskId,
            userId: event.userId,
            deviceId: event.deviceId,
            source: source(event),
            channel: channel(event),
            correlationId: correlationId(event),
            route: route(event),
            capability: capability(event),
            schemaVersion: schemaVersion(event),
            createdAt: event.createdAt ?: Instant.now(),
            firstEventAt: event.createdAt ?: Instant.now(),
            eventCount: 0
        )
        task.userId = task.userId ?: event.userId
        task.deviceId = task.deviceId ?: event.deviceId
        task.taskType = task.taskType ?: taskType(event)
        task.source = task.source ?: source(event)
        task.channel = task.channel ?: channel(event)
        task.correlationId = task.correlationId ?: correlationId(event)
        task.route = task.route ?: route(event)
        task.capability = task.capability ?: capability(event)
        task.schemaVersion = task.schemaVersion ?: schemaVersion(event)
        task.status = event.status ?: event.type ?: task.status
        task.level = event.level ?: task.level ?: 'info'
        task.title = task.title ?: title(event)
        task.commandText = task.commandText ?: commandText(event)
        task.recoverable = event.recoverable
        task.eventCount = (task.eventCount ?: 0) + 1
        task.firstEventAt = minInstant(task.firstEventAt, event.createdAt)
        task.lastEventAt = maxInstant(task.lastEventAt, event.createdAt)
        task.updatedAt = event.createdAt ?: Instant.now()
        if (isRequestEvent(event) && !task.requestPayload) {
            task.requestPayload = publicPayload(event)
        }
        if (isRunning(event) && !task.startedAt) {
            task.startedAt = event.createdAt ?: Instant.now()
        }
        if (isResultEvent(event)) {
            task.resultPayload = publicPayload(event)
        }
        if (isErrorEvent(event)) {
            task.errorPayload = publicPayload(event)
        }
        if (isTerminal(event)) {
            task.completedAt = event.createdAt ?: Instant.now()
        }
        return task
    }

    static String taskType(DeviceTaskEvent event) {
        Object commandType = event.payload?.commandType ?: event.payload?.attributes?.commandType
        return commandType?.toString() ?: event.type ?: ''
    }

    static String commandText(DeviceTaskEvent event) {
        Object text = event.payload?.text ?: event.payload?.attributes?.text
        return text?.toString() ?: ''
    }

    static String source(DeviceTaskEvent event) {
        return (event.payload?.source ?: event.payload?.attributes?.source ?: 'device').toString()
    }

    static String channel(DeviceTaskEvent event) {
        return (event.payload?.channel ?: event.payload?.transport ?: event.payload?.attributes?.channel ?: 'device').toString()
    }

    static String correlationId(DeviceTaskEvent event) {
        Object value = event.payload?.correlationId ?: event.payload?.commandId ?: event.payload?.attributes?.correlationId
        return value?.toString()
    }

    static String route(DeviceTaskEvent event) {
        Object value = event.payload?.route ?: event.payload?.routingKey ?: event.payload?.attributes?.route
        return value?.toString()
    }

    static String capability(DeviceTaskEvent event) {
        Object value = event.payload?.capability ?: event.payload?.commandType ?: event.payload?.attributes?.capability
        return value?.toString()
    }

    static String schemaVersion(DeviceTaskEvent event) {
        return (event.payload?.schemaVersion ?: event.payload?.attributes?.schemaVersion ?: '1').toString()
    }

    static String title(DeviceTaskEvent event) {
        return commandText(event) ?: event.message ?: taskType(event)
    }

    static Map<String, Object> publicPayload(DeviceTaskEvent event) {
        return (event.payload ?: [:]).collectEntries { key, value ->
            [(key.toString()): value]
        }.findAll { key, value ->
            key != 'eventSignature'
        } as Map<String, Object>
    }

    static boolean isRequestEvent(DeviceTaskEvent event) {
        return [DeviceProtocol.STATUS_QUEUED, DeviceProtocol.STATUS_ACCEPTED, DeviceProtocol.STATUS_NEEDS_APPROVAL].contains(event.type)
    }

    static boolean isRunning(DeviceTaskEvent event) {
        return event.status == DeviceProtocol.STATUS_RUNNING || event.type == DeviceProtocol.STATUS_RUNNING
    }

    static boolean isResultEvent(DeviceTaskEvent event) {
        return [
            DeviceProtocol.STATUS_COMPLETED,
            DeviceProtocol.STATUS_CLIENT_RESPONSE,
            DeviceProtocol.STATUS_INPUT_RECEIVED,
            DeviceProtocol.STATUS_APPROVED,
            DeviceProtocol.STATUS_REJECTED
        ].contains(event.type) || event.status == DeviceProtocol.STATUS_COMPLETED
    }

    static boolean isErrorEvent(DeviceTaskEvent event) {
        return event.type == DeviceProtocol.STATUS_FAILED || event.status == DeviceProtocol.STATUS_FAILED
    }

    static boolean isTerminal(DeviceTaskEvent event) {
        return [
            DeviceProtocol.STATUS_COMPLETED,
            DeviceProtocol.STATUS_FAILED,
            DeviceProtocol.STATUS_REJECTED
        ].contains(event.status)
    }

    private static Instant minInstant(Instant a, Instant b) {
        if (!a) return b
        if (!b) return a
        return a.isBefore(b) ? a : b
    }

    private static Instant maxInstant(Instant a, Instant b) {
        if (!a) return b
        if (!b) return a
        return a.isAfter(b) ? a : b
    }
}
