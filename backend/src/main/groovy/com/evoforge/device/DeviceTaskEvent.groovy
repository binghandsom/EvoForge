package com.evoforge.device

import groovy.transform.ToString

import java.time.Instant

@ToString(includeNames = true)
class DeviceTaskEvent {
    String eventId = UUID.randomUUID().toString()
    String taskId
    String userId
    String deviceId
    String type
    String status
    String level = 'info'
    String message
    String output
    boolean recoverable = false
    Map<String, Object> payload = [:]
    Instant createdAt = Instant.now()
}
