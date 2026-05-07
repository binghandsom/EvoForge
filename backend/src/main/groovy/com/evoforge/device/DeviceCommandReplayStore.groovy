package com.evoforge.device

import java.time.Instant

interface DeviceCommandReplayStore {
    boolean remember(String commandId, Instant seenAt, int ttlSeconds)

    void pruneExpired(int ttlSeconds)
}
