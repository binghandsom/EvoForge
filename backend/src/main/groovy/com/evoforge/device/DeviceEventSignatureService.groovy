package com.evoforge.device

import com.evoforge.core.EvoForgeProperties
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule
import com.fasterxml.jackson.databind.MapperFeature
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.SerializationFeature
import org.springframework.stereotype.Service

import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import java.nio.charset.StandardCharsets
import java.security.MessageDigest

@Service
class DeviceEventSignatureService {
    static final String SIGNATURE_ATTRIBUTE = 'eventSignature'
    static final String SIGNATURE_ALGORITHM = 'HmacSHA256'

    private final EvoForgeProperties properties
    private final ObjectMapper canonicalMapper

    DeviceEventSignatureService(EvoForgeProperties properties) {
        this.properties = properties
        this.canonicalMapper = new ObjectMapper()
            .registerModule(new JavaTimeModule())
            .configure(MapperFeature.SORT_PROPERTIES_ALPHABETICALLY, true)
            .configure(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS, true)
            .configure(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS, false)
    }

    boolean enabled() {
        return !!properties.deviceAgent.eventSigningSecret
    }

    void sign(DeviceTaskEvent event) {
        if (!enabled()) {
            return
        }
        event.payload = event.payload ?: [:]
        event.payload[SIGNATURE_ATTRIBUTE] = signatureFor(event)
    }

    boolean verify(DeviceTaskEvent event) {
        if (!enabled()) {
            return true
        }
        String actual = event.payload?.get(SIGNATURE_ATTRIBUTE)?.toString()
        if (!actual) {
            return false
        }
        String expected = signatureFor(event)
        return MessageDigest.isEqual(
            expected.getBytes(StandardCharsets.UTF_8),
            actual.getBytes(StandardCharsets.UTF_8)
        )
    }

    String signatureFor(DeviceTaskEvent event) {
        Mac mac = Mac.getInstance(SIGNATURE_ALGORITHM)
        mac.init(new SecretKeySpec(
            properties.deviceAgent.eventSigningSecret.getBytes(StandardCharsets.UTF_8),
            SIGNATURE_ALGORITHM
        ))
        byte[] digest = mac.doFinal(canonicalPayload(event).getBytes(StandardCharsets.UTF_8))
        return digest.collect { String.format('%02x', it & 0xff) }.join()
    }

    String canonicalPayload(DeviceTaskEvent event) {
        return canonicalMapper.writeValueAsString([
            eventId    : event.eventId,
            taskId     : event.taskId,
            userId     : event.userId,
            deviceId   : event.deviceId,
            type       : event.type,
            status     : event.status,
            level      : event.level,
            message    : event.message,
            output     : event.output,
            recoverable: event.recoverable,
            createdAt  : event.createdAt?.toString(),
            payload    : signedPayload(event.payload ?: [:])
        ])
    }

    private static Map<String, Object> signedPayload(Map payload) {
        return payload
            .findAll { it.key?.toString() != SIGNATURE_ATTRIBUTE }
            .collectEntries { key, value -> [(key.toString()): value] } as Map<String, Object>
    }
}
