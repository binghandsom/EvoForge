package com.evoforge.device

import groovy.transform.ToString

@ToString(includeNames = true)
class DeviceCommandMessage {
    String commandId
    String taskId
    String userId
    String deviceId
    String type = DeviceProtocol.TYPE_NATURAL_LANGUAGE_TASK
    String text
    String skillId
    String llm
    String codeModel
    boolean requiresApproval = false
    Map<String, Object> attributes = [:]
    String createdAt
}
