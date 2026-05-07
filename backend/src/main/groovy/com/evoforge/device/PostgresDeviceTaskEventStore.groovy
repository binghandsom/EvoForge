package com.evoforge.device

import com.evoforge.core.JsonColumns
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
class PostgresDeviceTaskEventStore implements DeviceTaskEventStore {
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
        jdbcTemplate.update('''
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
        return event
    }

    @Override
    List<DeviceTaskEvent> listForTask(String taskId) {
        return jdbcTemplate.query('''
            SELECT event_id, task_id, user_id, device_id, event_type, status, level, message, output, recoverable, payload::text AS payload, created_at
            FROM device_task_events
            WHERE task_id = ?
            ORDER BY created_at ASC
        ''', eventMapper(), taskId)
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

    private RowMapper<DeviceTaskEvent> eventMapper() {
        return { ResultSet rs, int rowNum -> mapEvent(rs) } as RowMapper<DeviceTaskEvent>
    }

    private RowMapper<DeviceTaskSummary> summaryMapper() {
        return { ResultSet rs, int rowNum -> mapSummary(rs) } as RowMapper<DeviceTaskSummary>
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

    private static Instant readInstant(ResultSet rs, String column) {
        Timestamp timestamp = rs.getTimestamp(column)
        return timestamp ? timestamp.toInstant() : null
    }
}
