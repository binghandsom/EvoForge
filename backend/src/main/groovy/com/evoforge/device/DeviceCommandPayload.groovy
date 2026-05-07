package com.evoforge.device

class DeviceCommandPayload {
    static Map<String, Object> from(DeviceCommandMessage command) {
        return [
            commandId       : command.commandId,
            commandType     : command.type,
            text            : command.text,
            skillId         : command.skillId,
            llm             : command.llm,
            codeModel       : command.codeModel,
            requiresApproval: command.requiresApproval,
            projectKey      : projectKey(command.attributes ?: [:]),
            attributes      : publicAttributes(command.attributes ?: [:])
        ].findAll { it.value != null } as Map<String, Object>
    }

    static DeviceCommandMessage toMessage(String taskId, DeviceTaskEvent event) {
        Map payload = event.payload ?: [:]
        Map attributes = payload.attributes instanceof Map ? new LinkedHashMap(payload.attributes as Map) : [:]
        return new DeviceCommandMessage(
            commandId: value(payload.commandId),
            taskId: taskId,
            userId: event.userId,
            deviceId: event.deviceId,
            type: value(payload.commandType) ?: DeviceProtocol.TYPE_NATURAL_LANGUAGE_TASK,
            text: value(payload.text),
            skillId: value(payload.skillId),
            llm: value(payload.llm),
            codeModel: value(payload.codeModel),
            requiresApproval: false,
            attributes: attributes
        )
    }

    private static Map<String, Object> publicAttributes(Map attributes) {
        return attributes
            .findAll { it.key?.toString() != DeviceCommandSignatureService.SIGNATURE_ATTRIBUTE }
            .collectEntries { key, value -> [(key.toString()): value] } as Map<String, Object>
    }

    private static String projectKey(Map attributes) {
        Object value = attributes.projectKey ?: attributes.workspaceKey ?: attributes.project
        String key = value == null ? '' : value.toString()
        return key ? key : null
    }

    private static String value(Object input) {
        return input == null ? null : input.toString()
    }
}
