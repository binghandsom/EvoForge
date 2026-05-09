package com.evoforge.tester

import com.evoforge.core.EvoForgeProperties
import groovy.transform.ToString

import java.time.Instant

@ToString(includeNames = true)
class TesterCapability {
    String recordId
    String projectKey
    String id
    String name
    String type = ''
    String workingDirectory = ''
    String command = ''
    boolean enabled = true
    int timeoutSeconds = 0
    String reason = ''
    List<String> covers = []
    List<String> tags = []
    String cost = ''
    String confidence = ''
    String evidenceParser = ''
    List<String> fallbackCommandIds = []
    List<String> repairScopes = []
    String source = 'manual'
    String optimizationNotes = ''
    int successCount = 0
    int failureCount = 0
    String lastStatus = ''
    Integer lastExitCode
    Long lastDurationMs
    String lastOutputExcerpt = ''
    Instant lastRunAt
    Map<String, Object> metadata = [:]
    Instant createdAt = Instant.now()
    Instant updatedAt = Instant.now()

    EvoForgeProperties.TesterCommand toCommand() {
        return new EvoForgeProperties.TesterCommand(
            id: id,
            name: name,
            type: type,
            workingDirectory: workingDirectory,
            command: command,
            enabled: enabled,
            timeoutSeconds: timeoutSeconds,
            reason: reason,
            covers: covers ?: [],
            tags: tags ?: [],
            cost: cost,
            confidence: confidence,
            evidenceParser: evidenceParser,
            fallbackCommandIds: fallbackCommandIds ?: [],
            repairScopes: repairScopes ?: []
        )
    }

    Map<String, Object> toView() {
        return [
            recordId          : recordId,
            projectKey        : projectKey,
            id                : id,
            name              : name,
            type              : type,
            workingDirectory  : workingDirectory,
            command           : command,
            enabled           : enabled,
            timeoutSeconds    : timeoutSeconds,
            reason            : reason,
            covers            : covers ?: [],
            tags              : tags ?: [],
            cost              : cost,
            confidence        : confidence,
            evidenceParser    : evidenceParser,
            fallbackCommandIds: fallbackCommandIds ?: [],
            repairScopes      : repairScopes ?: [],
            source            : source,
            optimizationNotes : optimizationNotes,
            successCount      : successCount,
            failureCount      : failureCount,
            lastStatus        : lastStatus,
            lastExitCode      : lastExitCode,
            lastDurationMs    : lastDurationMs,
            lastOutputExcerpt : lastOutputExcerpt,
            lastRunAt         : lastRunAt?.toString(),
            metadata          : metadata ?: [:],
            createdAt         : createdAt?.toString(),
            updatedAt         : updatedAt?.toString()
        ].findAll { it.value != null && it.value != '' && (!(it.value instanceof Collection) || !it.value.isEmpty()) && (!(it.value instanceof Map) || !it.value.isEmpty()) } as Map<String, Object>
    }
}

