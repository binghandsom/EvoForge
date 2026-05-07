package com.evoforge.device

import com.evoforge.core.EvoForgeProperties
import com.fasterxml.jackson.databind.MapperFeature
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.SerializationFeature
import org.springframework.stereotype.Service

import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.time.Duration
import java.time.Instant

@Service
class DeviceCommandSignatureService {
    static final String SIGNATURE_ATTRIBUTE = 'signature'
    static final String SIGNATURE_ALGORITHM = 'HmacSHA256'

    private final EvoForgeProperties properties
    private final DeviceCommandReplayStore replayStore
    private final ObjectMapper canonicalMapper

    DeviceCommandSignatureService(EvoForgeProperties properties, DeviceCommandReplayStore replayStore) {
        this.properties = properties
        this.replayStore = replayStore
        this.canonicalMapper = new ObjectMapper()
            .configure(MapperFeature.SORT_PROPERTIES_ALPHABETICALLY, true)
            .configure(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS, true)
    }

    boolean enabled() {
        return !!properties.deviceAgent.commandSigningSecret
    }

    void sign(DeviceCommandMessage command) {
        if (!enabled()) {
            return
        }
        command.commandId = command.commandId ?: UUID.randomUUID().toString()
        command.attributes = command.attributes ?: [:]
        command.createdAt = command.createdAt ?: Instant.now().toString()
        command.attributes[SIGNATURE_ATTRIBUTE] = signatureFor(command)
    }

    boolean verify(DeviceCommandMessage command) {
        return verifyDetailed(command).valid
    }

    DeviceCommandSignatureVerification verifyDetailed(DeviceCommandMessage command) {
        if (!enabled()) {
            return DeviceCommandSignatureVerification.ok()
        }
        if (!command.commandId) {
            return DeviceCommandSignatureVerification.rejected('Missing command id')
        }
        DeviceCommandSignatureVerification freshness = verifyFreshness(command)
        if (!freshness.valid) {
            return freshness
        }
        String expected = signatureFor(command)
        String actual = command.attributes?.get(SIGNATURE_ATTRIBUTE)?.toString()
        if (!actual) {
            return DeviceCommandSignatureVerification.rejected('Missing command signature')
        }
        boolean valid = MessageDigest.isEqual(
            expected.getBytes(StandardCharsets.UTF_8),
            actual.getBytes(StandardCharsets.UTF_8)
        )
        if (!valid) {
            return DeviceCommandSignatureVerification.rejected('Invalid command signature')
        }
        return remember(command)
    }

    String signatureFor(DeviceCommandMessage command) {
        Mac mac = Mac.getInstance(SIGNATURE_ALGORITHM)
        mac.init(new SecretKeySpec(
            properties.deviceAgent.commandSigningSecret.getBytes(StandardCharsets.UTF_8),
            SIGNATURE_ALGORITHM
        ))
        byte[] digest = mac.doFinal(canonicalPayload(command).getBytes(StandardCharsets.UTF_8))
        return digest.collect { String.format('%02x', it & 0xff) }.join()
    }

    private String canonicalPayload(DeviceCommandMessage command) {
        return canonicalMapper.writeValueAsString([
            codeModel       : command.codeModel,
            commandId       : command.commandId,
            createdAt       : command.createdAt,
            deviceId        : command.deviceId,
            llm             : command.llm,
            requiresApproval: command.requiresApproval,
            skillId         : command.skillId,
            taskId          : command.taskId,
            text            : command.text,
            type            : command.type,
            userId          : command.userId,
            attributes      : signedAttributes(command.attributes ?: [:])
        ])
    }

    private static Map<String, Object> signedAttributes(Map<String, Object> attributes) {
        return attributes
            .findAll { it.key != SIGNATURE_ATTRIBUTE }
            .collectEntries { key, value -> [(key.toString()): value] } as Map<String, Object>
    }

    private DeviceCommandSignatureVerification verifyFreshness(DeviceCommandMessage command) {
        if (!command.createdAt) {
            return DeviceCommandSignatureVerification.rejected('Missing command timestamp')
        }
        Instant createdAt
        try {
            createdAt = Instant.parse(command.createdAt)
        } catch (Exception ignored) {
            return DeviceCommandSignatureVerification.rejected('Invalid command timestamp')
        }
        long ttlSeconds = Math.max(1, properties.deviceAgent.commandSignatureTtlSeconds)
        Duration skew = Duration.between(createdAt, Instant.now()).abs()
        if (skew.seconds > ttlSeconds) {
            return DeviceCommandSignatureVerification.rejected('Expired command signature')
        }
        return DeviceCommandSignatureVerification.ok()
    }

    private DeviceCommandSignatureVerification remember(DeviceCommandMessage command) {
        int ttlSeconds = Math.max(1, properties.deviceAgent.commandSignatureTtlSeconds)
        boolean remembered = replayStore.remember(command.commandId, Instant.now(), ttlSeconds)
        return remembered
            ? DeviceCommandSignatureVerification.ok()
            : DeviceCommandSignatureVerification.rejected('Replay command signature')
    }
}
