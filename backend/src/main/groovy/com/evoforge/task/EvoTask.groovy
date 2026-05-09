package com.evoforge.task

import groovy.transform.ToString

import java.time.Instant

@ToString(includeNames = true)
class EvoTask {
    String taskId
    String userId
    String deviceId
    String taskType
    String source = 'device'
    String channel = 'device'
    String correlationId
    String route
    String capability
    String schemaVersion = '1'
    String status
    String level = 'info'
    String title
    String commandText
    boolean recoverable = false
    Map<String, Object> requestPayload = [:]
    Map<String, Object> resultPayload = [:]
    Map<String, Object> errorPayload = [:]
    int eventCount = 0
    Instant firstEventAt
    Instant lastEventAt
    Instant startedAt
    Instant completedAt
    Instant createdAt
    Instant updatedAt
}
