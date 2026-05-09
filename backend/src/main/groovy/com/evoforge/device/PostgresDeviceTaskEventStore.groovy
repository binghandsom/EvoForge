package com.evoforge.device

import com.evoforge.core.JsonColumns
import com.evoforge.task.EvoTask
import com.evoforge.task.EvoTaskProjector
import com.evoforge.task.EvoTaskStore
import com.fasterxml.jackson.databind.ObjectMapper
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.core.RowMapper
import org.springframework.stereotype.Component

import java.sql.ResultSet
import java.sql.Timestamp
import java.time.Instant

@Component
@ConditionalOnProperty(prefix = 'evoforge.skills', name = 'storageBackend', havingValue = 'postgres', matchIfMissing = true)
class PostgresDeviceTaskEventStore implements DeviceTaskEventStore, EvoTaskStore {
    private final JdbcTemplate jdbcTemplate
    private final ObjectMapper objectMapper

    PostgresDeviceTaskEventStore(JdbcTemplate jdbcTemplate, ObjectMapper objectMapper) {
        this.jdbcTemplate = jdbcTemplate
        this.objectMapper = objectMapper
    }

    @Override
    DeviceTaskEvent append(DeviceTaskEvent event) {
        event.eventId = event.eventId ?: UUID.randomUUID().toString()
        event.createdAt = event.createdAt ?: Instant.now()
        int inserted = jdbcTemplate.update('''
            INSERT INTO device_task_events (
                event_id, task_id, user_id, device_id, event_type, status, level, message, output, recoverable, payload, created_at
            )
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?::jsonb, ?)
            ON CONFLICT (event_id) DO NOTHING
        ''',
            event.eventId,
            event.taskId,
            event.userId,
            event.deviceId,
            event.type,
            event.status,
            event.level ?: 'info',
            event.message,
            event.output,
            event.recoverable,
            JsonColumns.writeMap(objectMapper, event.payload),
            Timestamp.from(event.createdAt)
        )
        if (inserted > 0 && event.taskId) {
            upsertTask(event)
        }
        return event
    }

    @Override
    List<DeviceTaskEvent> listForTask(String taskId) {
        return jdbcTemplate.query('''
            SELECT event_id, task_id, user_id, device_id, event_type, status, level, message, output, recoverable, payload::text AS payload, created_at
            FROM device_task_events
            WHERE task_id = ?
            ORDER BY created_at ASC, event_id ASC
        ''', eventMapper(), taskId)
    }

    @Override
    DeviceTaskEventPage listForTaskPage(String taskId, int limit, String beforeCursor, String afterCursor) {
        int safeLimit = Math.max(1, Math.min(limit, 200))
        int queryLimit = safeLimit + 1
        DeviceTaskEventPage.Cursor before = DeviceTaskEventPage.parseCursor(beforeCursor)
        DeviceTaskEventPage.Cursor after = DeviceTaskEventPage.parseCursor(afterCursor)
        List<DeviceTaskEvent> events
        boolean hasMoreBefore = false
        boolean hasMoreAfter = false

        if (after) {
            events = jdbcTemplate.query('''
                SELECT event_id, task_id, user_id, device_id, event_type, status, level, message, output, recoverable, payload::text AS payload, created_at
                FROM device_task_events
                WHERE task_id = ?
                  AND (created_at > ? OR (created_at = ? AND event_id > ?))
                ORDER BY created_at ASC, event_id ASC
                LIMIT ?
            ''', eventMapper(), taskId, Timestamp.from(after.createdAt), Timestamp.from(after.createdAt), after.eventId, queryLimit)
            hasMoreAfter = events.size() > safeLimit
            events = events.take(safeLimit)
            return DeviceTaskEventPage.fromItems(events, safeLimit, false, hasMoreAfter)
        }

        if (before) {
            events = jdbcTemplate.query('''
                SELECT event_id, task_id, user_id, device_id, event_type, status, level, message, output, recoverable, payload::text AS payload, created_at
                FROM device_task_events
                WHERE task_id = ?
                  AND (created_at < ? OR (created_at = ? AND event_id < ?))
                ORDER BY created_at DESC, event_id DESC
                LIMIT ?
            ''', eventMapper(), taskId, Timestamp.from(before.createdAt), Timestamp.from(before.createdAt), before.eventId, queryLimit)
            hasMoreBefore = events.size() > safeLimit
            events = events.take(safeLimit)
            return DeviceTaskEventPage.fromItems(events, safeLimit, hasMoreBefore, false)
        }

        events = jdbcTemplate.query('''
            SELECT event_id, task_id, user_id, device_id, event_type, status, level, message, output, recoverable, payload::text AS payload, created_at
            FROM device_task_events
            WHERE task_id = ?
            ORDER BY created_at DESC, event_id DESC
            LIMIT ?
        ''', eventMapper(), taskId, queryLimit)
        hasMoreBefore = events.size() > safeLimit
        events = events.take(safeLimit)
        return DeviceTaskEventPage.fromItems(events, safeLimit, hasMoreBefore, false)
    }

    @Override
    List<DeviceTaskSummary> listRecentTasks(int limit) {
        int safeLimit = Math.max(0, Math.min(limit, 200))
        return jdbcTemplate.query('''
            WITH latest AS (
                SELECT DISTINCT ON (task_id)
                    task_id, user_id, device_id, event_type, status, level, message, recoverable, payload, created_at
                FROM device_task_events
                ORDER BY task_id, created_at DESC
            ),
            counts AS (
                SELECT task_id, COUNT(*) AS event_count, MIN(created_at) AS first_event_at
                FROM device_task_events
                GROUP BY task_id
            ),
            firsts AS (
                SELECT DISTINCT ON (task_id)
                    task_id,
                    COALESCE(payload->>'commandType', event_type) AS command_type,
                    payload->>'text' AS command_text
                FROM device_task_events
                ORDER BY task_id, created_at ASC
            )
            SELECT
                latest.task_id,
                latest.user_id,
                latest.device_id,
                COALESCE(firsts.command_type, latest.payload->>'commandType', latest.event_type) AS command_type,
                latest.status,
                latest.level,
                latest.message,
                COALESCE(firsts.command_text, latest.payload->>'text') AS command_text,
                latest.recoverable,
                counts.event_count,
                counts.first_event_at,
                latest.created_at AS last_event_at
            FROM latest
            JOIN counts ON counts.task_id = latest.task_id
            LEFT JOIN firsts ON firsts.task_id = latest.task_id
            ORDER BY latest.created_at DESC
            LIMIT ?
        ''', summaryMapper(), safeLimit)
    }

    @Override
    EvoTask find(String taskId) {
        List<EvoTask> tasks = jdbcTemplate.query('''
            SELECT task_id, user_id, device_id, task_type, source, channel, correlation_id, route, capability, schema_version,
                   status, level, title, command_text, recoverable,
                   request_payload::text AS request_payload,
                   result_payload::text AS result_payload,
                   error_payload::text AS error_payload,
                   event_count, first_event_at, last_event_at, started_at, completed_at, created_at, updated_at
            FROM evo_tasks
            WHERE task_id = ?
        ''', taskMapper(), taskId)
        return tasks ? tasks.first() : null
    }

    @Override
    List<EvoTask> listRecent(int limit) {
        int safeLimit = Math.max(0, Math.min(limit, 200))
        return jdbcTemplate.query('''
            SELECT task_id, user_id, device_id, task_type, source, channel, correlation_id, route, capability, schema_version,
                   status, level, title, command_text, recoverable,
                   request_payload::text AS request_payload,
                   result_payload::text AS result_payload,
                   error_payload::text AS error_payload,
                   event_count, first_event_at, last_event_at, started_at, completed_at, created_at, updated_at
            FROM evo_tasks
            ORDER BY last_event_at DESC NULLS LAST, created_at DESC
            LIMIT ?
        ''', taskMapper(), safeLimit)
    }

    private void upsertTask(DeviceTaskEvent event) {
        Instant eventTime = event.createdAt ?: Instant.now()
        Map<String, Object> requestPayload = EvoTaskProjector.isRequestEvent(event) ? EvoTaskProjector.publicPayload(event) : [:]
        Map<String, Object> resultPayload = EvoTaskProjector.isResultEvent(event) ? EvoTaskProjector.publicPayload(event) : [:]
        Map<String, Object> errorPayload = EvoTaskProjector.isErrorEvent(event) ? EvoTaskProjector.publicPayload(event) : [:]
        Timestamp startedAt = EvoTaskProjector.isRunning(event) ? Timestamp.from(eventTime) : null
        Timestamp completedAt = EvoTaskProjector.isTerminal(event) ? Timestamp.from(eventTime) : null
        jdbcTemplate.update('''
            INSERT INTO evo_tasks (
                task_id, user_id, device_id, task_type, source, channel, correlation_id, route, capability, schema_version,
                status, level, title, command_text, recoverable,
                request_payload, result_payload, error_payload, event_count, first_event_at, last_event_at,
                started_at, completed_at, created_at, updated_at
            )
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?::jsonb, ?::jsonb, ?::jsonb, 1, ?, ?, ?, ?, ?, ?)
            ON CONFLICT (task_id) DO UPDATE SET
                user_id = COALESCE(evo_tasks.user_id, EXCLUDED.user_id),
                device_id = COALESCE(evo_tasks.device_id, EXCLUDED.device_id),
                task_type = CASE WHEN evo_tasks.task_type = '' THEN EXCLUDED.task_type ELSE evo_tasks.task_type END,
                source = CASE WHEN evo_tasks.source = '' THEN EXCLUDED.source ELSE evo_tasks.source END,
                channel = CASE WHEN evo_tasks.channel = '' THEN EXCLUDED.channel ELSE evo_tasks.channel END,
                correlation_id = CASE WHEN evo_tasks.correlation_id = '' THEN EXCLUDED.correlation_id ELSE evo_tasks.correlation_id END,
                route = CASE WHEN evo_tasks.route = '' THEN EXCLUDED.route ELSE evo_tasks.route END,
                capability = CASE WHEN evo_tasks.capability = '' THEN EXCLUDED.capability ELSE evo_tasks.capability END,
                schema_version = CASE WHEN evo_tasks.schema_version = '' THEN EXCLUDED.schema_version ELSE evo_tasks.schema_version END,
                status = EXCLUDED.status,
                level = EXCLUDED.level,
                title = CASE WHEN evo_tasks.title = '' THEN EXCLUDED.title ELSE evo_tasks.title END,
                command_text = CASE WHEN evo_tasks.command_text = '' THEN EXCLUDED.command_text ELSE evo_tasks.command_text END,
                recoverable = EXCLUDED.recoverable,
                request_payload = CASE
                    WHEN evo_tasks.request_payload = '{}'::jsonb THEN EXCLUDED.request_payload
                    ELSE evo_tasks.request_payload
                END,
                result_payload = CASE
                    WHEN EXCLUDED.result_payload <> '{}'::jsonb THEN EXCLUDED.result_payload
                    ELSE evo_tasks.result_payload
                END,
                error_payload = CASE
                    WHEN EXCLUDED.error_payload <> '{}'::jsonb THEN EXCLUDED.error_payload
                    ELSE evo_tasks.error_payload
                END,
                event_count = evo_tasks.event_count + 1,
                first_event_at = COALESCE(LEAST(evo_tasks.first_event_at, EXCLUDED.first_event_at), evo_tasks.first_event_at, EXCLUDED.first_event_at),
                last_event_at = COALESCE(GREATEST(evo_tasks.last_event_at, EXCLUDED.last_event_at), evo_tasks.last_event_at, EXCLUDED.last_event_at),
                started_at = COALESCE(evo_tasks.started_at, EXCLUDED.started_at),
                completed_at = COALESCE(EXCLUDED.completed_at, evo_tasks.completed_at),
                updated_at = now()
        ''',
            event.taskId,
            event.userId,
            event.deviceId,
            EvoTaskProjector.taskType(event),
            EvoTaskProjector.source(event),
            EvoTaskProjector.channel(event),
            EvoTaskProjector.correlationId(event) ?: '',
            EvoTaskProjector.route(event) ?: '',
            EvoTaskProjector.capability(event) ?: '',
            EvoTaskProjector.schemaVersion(event),
            event.status ?: event.type ?: '',
            event.level ?: 'info',
            EvoTaskProjector.title(event),
            EvoTaskProjector.commandText(event),
            event.recoverable,
            JsonColumns.writeMap(objectMapper, requestPayload),
            JsonColumns.writeMap(objectMapper, resultPayload),
            JsonColumns.writeMap(objectMapper, errorPayload),
            Timestamp.from(eventTime),
            Timestamp.from(eventTime),
            startedAt,
            completedAt,
            Timestamp.from(eventTime),
            Timestamp.from(eventTime)
        )
    }

    private RowMapper<DeviceTaskEvent> eventMapper() {
        return { ResultSet rs, int rowNum -> mapEvent(rs) } as RowMapper<DeviceTaskEvent>
    }

    private RowMapper<DeviceTaskSummary> summaryMapper() {
        return { ResultSet rs, int rowNum -> mapSummary(rs) } as RowMapper<DeviceTaskSummary>
    }

    private RowMapper<EvoTask> taskMapper() {
        return { ResultSet rs, int rowNum -> mapTask(rs) } as RowMapper<EvoTask>
    }

    private DeviceTaskEvent mapEvent(ResultSet rs) {
        return new DeviceTaskEvent(
            eventId: rs.getString('event_id'),
            taskId: rs.getString('task_id'),
            userId: rs.getString('user_id'),
            deviceId: rs.getString('device_id'),
            type: rs.getString('event_type'),
            status: rs.getString('status'),
            level: rs.getString('level'),
            message: rs.getString('message'),
            output: rs.getString('output'),
            recoverable: rs.getBoolean('recoverable'),
            payload: JsonColumns.readMap(objectMapper, rs.getString('payload')),
            createdAt: readInstant(rs, 'created_at')
        )
    }

    private DeviceTaskSummary mapSummary(ResultSet rs) {
        return new DeviceTaskSummary(
            taskId: rs.getString('task_id'),
            userId: rs.getString('user_id'),
            deviceId: rs.getString('device_id'),
            type: rs.getString('command_type'),
            status: rs.getString('status'),
            level: rs.getString('level'),
            message: rs.getString('message'),
            commandText: rs.getString('command_text'),
            recoverable: rs.getBoolean('recoverable'),
            eventCount: rs.getInt('event_count'),
            firstEventAt: readInstant(rs, 'first_event_at'),
            lastEventAt: readInstant(rs, 'last_event_at')
        )
    }

    private EvoTask mapTask(ResultSet rs) {
        return new EvoTask(
            taskId: rs.getString('task_id'),
            userId: rs.getString('user_id'),
            deviceId: rs.getString('device_id'),
            taskType: rs.getString('task_type'),
            source: rs.getString('source'),
            channel: rs.getString('channel'),
            correlationId: rs.getString('correlation_id'),
            route: rs.getString('route'),
            capability: rs.getString('capability'),
            schemaVersion: rs.getString('schema_version'),
            status: rs.getString('status'),
            level: rs.getString('level'),
            title: rs.getString('title'),
            commandText: rs.getString('command_text'),
            recoverable: rs.getBoolean('recoverable'),
            requestPayload: JsonColumns.readMap(objectMapper, rs.getString('request_payload')),
            resultPayload: JsonColumns.readMap(objectMapper, rs.getString('result_payload')),
            errorPayload: JsonColumns.readMap(objectMapper, rs.getString('error_payload')),
            eventCount: rs.getInt('event_count'),
            firstEventAt: readInstant(rs, 'first_event_at'),
            lastEventAt: readInstant(rs, 'last_event_at'),
            startedAt: readInstant(rs, 'started_at'),
            completedAt: readInstant(rs, 'completed_at'),
            createdAt: readInstant(rs, 'created_at'),
            updatedAt: readInstant(rs, 'updated_at')
        )
    }

    private static Instant readInstant(ResultSet rs, String column) {
        Timestamp timestamp = rs.getTimestamp(column)
        return timestamp ? timestamp.toInstant() : null
    }
}
