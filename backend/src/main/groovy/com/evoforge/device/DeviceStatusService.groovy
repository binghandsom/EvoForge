package com.evoforge.device

import com.evoforge.core.EvoForgeProperties
import com.evoforge.tester.TesterCapabilityService
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.stereotype.Service

@Service
class DeviceStatusService {
    private final EvoForgeProperties properties
    private final TesterCapabilityService testerCapabilityService

    DeviceStatusService(EvoForgeProperties properties) {
        this(properties, null)
    }

    @Autowired
    DeviceStatusService(EvoForgeProperties properties,
                        TesterCapabilityService testerCapabilityService) {
        this.properties = properties
        this.testerCapabilityService = testerCapabilityService
    }

    Map<String, Object> status() {
        return [
            enabled         : properties.deviceAgent.enabled,
            userId          : properties.deviceAgent.userId,
            deviceId        : properties.deviceAgent.deviceId,
            commandExchange : properties.deviceAgent.commandExchange,
            eventExchange   : properties.deviceAgent.eventExchange,
            commandQueue    : commandQueue(),
            requestQueue    : requestQueue(),
            eventQueue      : eventQueue(),
            commandRoutingKey: commandRoutingKey(),
            requestRoutingKey: requestRoutingKey(),
            eventRoutingKey : eventRoutingKey(),
            heartbeatSeconds: properties.deviceAgent.heartbeatSeconds,
            commandTypes    : DeviceProtocol.commandTypes(),
            commandSigning  : [
                enabled                   : !!properties.deviceAgent.commandSigningSecret,
                ttlSeconds                : properties.deviceAgent.commandSignatureTtlSeconds,
                replayStore               : replayStoreName(),
                persistentReplayProtection: replayStoreName() == 'postgres'
            ],
            eventSigning    : [
                enabled: !!properties.deviceAgent.eventSigningSecret
            ],
            codexTask      : [
                enabled         : properties.codexTask.enabled,
                requiresApproval: properties.codexTask.requiresApproval,
                defaultWorkspace: properties.codexTask.defaultWorkspace,
                workingDirectory: properties.codexTask.workingDirectory,
                workspaces      : codexWorkspaces(),
                timeoutSeconds  : properties.codexTask.timeoutSeconds
            ],
            tester        : [
                enabled         : properties.tester.enabled,
                requiresApproval: properties.tester.requiresApproval,
                timeoutSeconds  : properties.tester.timeoutSeconds,
                commandSource   : testerCapabilityService ? 'database-quality-capabilities' : 'legacy-config-fallback',
                autoDiscoverEnabled: properties.tester.autoDiscoverEnabled,
                modelDiscoveryEnabled: properties.tester.modelDiscoveryEnabled,
                autoOptimizeEnabled: properties.tester.autoOptimizeEnabled,
                projectCommands : testerProjectCommands()
            ],
            capabilities    : DeviceProtocol.capabilities()
        ]
    }

    String commandQueue() {
        return (properties.deviceAgent.commandQueue ?: "evoforge.device.${properties.deviceAgent.deviceId}.commands").toString()
    }

    String eventQueue() {
        return (properties.deviceAgent.eventQueue ?: "evoforge.device.${properties.deviceAgent.deviceId}.events").toString()
    }

    String requestQueue() {
        return (properties.deviceAgent.requestQueue ?: "evoforge.device.${properties.deviceAgent.deviceId}.requests").toString()
    }

    String commandRoutingKey() {
        return (properties.deviceAgent.commandRoutingKey ?: "user.${properties.deviceAgent.userId}.device.${properties.deviceAgent.deviceId}.command").toString()
    }

    String eventRoutingKey() {
        return (properties.deviceAgent.eventRoutingKey ?: "user.${properties.deviceAgent.userId}.device.${properties.deviceAgent.deviceId}.event").toString()
    }

    String requestRoutingKey() {
        return (properties.deviceAgent.requestRoutingKey ?: "user.${properties.deviceAgent.userId}.device.${properties.deviceAgent.deviceId}.request").toString()
    }

    private String replayStoreName() {
        return properties.skills.storageBackend == 'postgres' ? 'postgres' : 'memory'
    }

    private List<Map<String, String>> codexWorkspaces() {
        return (properties.codexTask.workspaces ?: [:])
            .collect { key, path -> [key: key.toString(), path: path?.toString() ?: ''] }
            .sort { a, b -> a.key <=> b.key }
    }

    private List<Map<String, Object>> testerProjectCommands() {
        if (testerCapabilityService) {
            return testerCapabilityService.list('')
                .groupBy { it.projectKey }
                .collect { key, capabilities ->
                    [
                        projectKey: key.toString(),
                        commands  : capabilities.collect { it.toView() }
                    ] as Map<String, Object>
                }
                .sort { a, b -> a.projectKey <=> b.projectKey }
        }
        return (properties.tester.projectCommands ?: [:])
            .collect { key, commands ->
                [
                    projectKey: key.toString(),
                    commands  : (commands ?: []).findAll { it?.enabled != false }.collect { command ->
                        [
                            id              : command.id ?: command.name ?: command.command,
                            name            : command.name ?: command.id ?: command.command,
                            type            : command.type ?: '',
                            covers          : command.covers ?: [],
                            tags            : command.tags ?: [],
                            cost            : command.cost ?: '',
                            confidence      : command.confidence ?: '',
                            evidenceParser  : command.evidenceParser ?: '',
                            workingDirectory: command.workingDirectory ?: '',
                            command         : command.command ?: '',
                            reason          : command.reason ?: ''
                        ].findAll { it.value != null && it.value != '' && (!(it.value instanceof Collection) || !it.value.isEmpty()) }
                    }
                ] as Map<String, Object>
            }
            .sort { a, b -> a.projectKey <=> b.projectKey }
    }
}
