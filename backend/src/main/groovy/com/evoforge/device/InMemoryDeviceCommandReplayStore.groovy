package com.evoforge.device

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.stereotype.Component

import java.time.Instant
import java.util.concurrent.ConcurrentHashMap

@Component
@ConditionalOnProperty(prefix = 'evoforge.skills', name = 'storageBackend', havingValue = 'file')
class InMemoryDeviceCommandReplayStore implements DeviceCommandReplayStore {
    private final Map<String, Instant> seenCommandIds = new ConcurrentHashMap<>()

    @Override
    boolean remember(String commandId, Instant seenAt, int ttlSeconds) {
        pruneExpired(ttlSeconds)
        return seenCommandIds.putIfAbsent(commandId, seenAt) == null
    }

    @Override
    void pruneExpired(int ttlSeconds) {
        Instant cutoff = Instant.now().minusSeconds(Math.max(1, ttlSeconds))
        seenCommandIds.entrySet().removeIf { it.value.isBefore(cutoff) }
    }
}
