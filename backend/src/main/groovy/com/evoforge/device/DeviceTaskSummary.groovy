package com.evoforge.device

import groovy.transform.ToString

import java.time.Instant

@ToString(includeNames = true)
class DeviceTaskSummary {
    String taskId
    String userId
    String deviceId
    String type
    String status
    String level = 'info'
    String message
    String commandText
    boolean recoverable = false
    int eventCount = 0
    Instant firstEventAt
    Instant lastEventAt
}
