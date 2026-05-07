package com.evoforge.device

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Component

import java.sql.Timestamp
import java.time.Instant

@Component
@ConditionalOnProperty(prefix = 'evoforge.skills', name = 'storageBackend', havingValue = 'postgres', matchIfMissing = true)
class PostgresDeviceCommandReplayStore implements DeviceCommandReplayStore {
    private final JdbcTemplate jdbcTemplate

    PostgresDeviceCommandReplayStore(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate
    }

    @Override
    boolean remember(String commandId, Instant seenAt, int ttlSeconds) {
        pruneExpired(ttlSeconds)
        int inserted = jdbcTemplate.update('''
            INSERT INTO device_command_replay (command_id, seen_at)
            VALUES (?, ?)
            ON CONFLICT (command_id) DO NOTHING
        ''', commandId, Timestamp.from(seenAt))
        return inserted == 1
    }

    @Override
    void pruneExpired(int ttlSeconds) {
        Instant cutoff = Instant.now().minusSeconds(Math.max(1, ttlSeconds))
        jdbcTemplate.update('DELETE FROM device_command_replay WHERE seen_at < ?', Timestamp.from(cutoff))
    }
}
