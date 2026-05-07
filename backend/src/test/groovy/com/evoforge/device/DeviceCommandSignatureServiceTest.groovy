package com.evoforge.device

import com.evoforge.core.EvoForgeProperties
import org.junit.jupiter.api.Test

import java.time.Instant

import static org.junit.jupiter.api.Assertions.*

class DeviceCommandSignatureServiceTest {

    @Test
    void verifiesSignedCommandAndRejectsTampering() {
        EvoForgeProperties properties = new EvoForgeProperties()
        properties.deviceAgent.commandSigningSecret = 'unit-test-secret'
        DeviceCommandSignatureService service = newService(properties)

        DeviceCommandMessage command = new DeviceCommandMessage(
            commandId: 'command-1',
            taskId: 'task-1',
            userId: 'user-1',
            deviceId: 'device-1',
            type: DeviceProtocol.TYPE_NATURAL_LANGUAGE_TASK,
            text: 'run this',
            skillId: 'skill-1',
            llm: 'mock-llm',
            codeModel: 'mock-code',
            requiresApproval: true,
            createdAt: Instant.now().toString(),
            attributes: [priority: 'high', nested: [b: 2, a: 1]]
        )

        service.sign(command)

        assertTrue(service.verify(command))

        DeviceCommandMessage textTampered = copyCommand(command)
        textTampered.text = 'run something else'
        assertFalse(newService().verify(textTampered))

        DeviceCommandMessage approvalTampered = copyCommand(command)
        approvalTampered.requiresApproval = false
        assertFalse(newService().verify(approvalTampered))

        DeviceCommandMessage attributesTampered = copyCommand(command)
        attributesTampered.attributes.priority = 'low'
        assertFalse(newService().verify(attributesTampered))
    }

    @Test
    void signatureAttributeIsExcludedFromCanonicalPayload() {
        EvoForgeProperties properties = new EvoForgeProperties()
        properties.deviceAgent.commandSigningSecret = 'unit-test-secret'
        DeviceCommandSignatureService service = newService(properties)
        DeviceCommandMessage command = new DeviceCommandMessage(
            commandId: 'command-2',
            taskId: 'task-2',
            userId: 'user-1',
            deviceId: 'device-1',
            type: DeviceProtocol.TYPE_NATURAL_LANGUAGE_TASK,
            text: 'hello',
            createdAt: Instant.now().toString(),
            attributes: [foo: 'bar']
        )

        service.sign(command)
        String signature = command.attributes.signature
        command.attributes.signature = "${signature}-transport-copy".toString()

        assertFalse(service.verify(command))
        command.attributes.signature = signature
        assertTrue(service.verify(command))
    }

    @Test
    void disabledSigningAcceptsUnsignedCommand() {
        EvoForgeProperties properties = new EvoForgeProperties()
        DeviceCommandSignatureService service = newService(properties)

        assertTrue(service.verify(new DeviceCommandMessage(text: 'unsigned')))
    }

    @Test
    void rejectsExpiredAndFutureSignedCommands() {
        EvoForgeProperties properties = new EvoForgeProperties()
        properties.deviceAgent.commandSigningSecret = 'unit-test-secret'
        properties.deviceAgent.commandSignatureTtlSeconds = 60
        DeviceCommandSignatureService service = newService(properties)

        DeviceCommandMessage expired = signedCommand(service, Instant.now().minusSeconds(120))
        assertFalse(service.verifyDetailed(expired).valid)
        assertEquals('Expired command signature', service.verifyDetailed(expired).reason)

        DeviceCommandMessage future = signedCommand(service, Instant.now().plusSeconds(120))
        assertFalse(service.verifyDetailed(future).valid)
        assertEquals('Expired command signature', service.verifyDetailed(future).reason)
    }

    @Test
    void fillsMissingTimestampWhenSigning() {
        EvoForgeProperties properties = new EvoForgeProperties()
        properties.deviceAgent.commandSigningSecret = 'unit-test-secret'
        DeviceCommandSignatureService service = newService(properties)

        DeviceCommandMessage missingTimestamp = new DeviceCommandMessage(
            commandId: 'command-missing-time',
            taskId: 'task-missing-time',
            userId: 'user-1',
            deviceId: 'device-1',
            type: DeviceProtocol.TYPE_NATURAL_LANGUAGE_TASK,
            text: 'hello'
        )
        service.sign(missingTimestamp)
        assertNotNull(missingTimestamp.createdAt)
        assertTrue(service.verifyDetailed(missingTimestamp).valid)
    }

    @Test
    void rejectsMissingOrInvalidTimestampWhenVerifyingExternalCommand() {
        EvoForgeProperties properties = new EvoForgeProperties()
        properties.deviceAgent.commandSigningSecret = 'unit-test-secret'
        DeviceCommandSignatureService service = newService(properties)

        DeviceCommandMessage missingTimestamp = new DeviceCommandMessage(
            commandId: 'command-missing-time-external',
            taskId: 'task-missing-time',
            userId: 'user-1',
            deviceId: 'device-1',
            type: DeviceProtocol.TYPE_NATURAL_LANGUAGE_TASK,
            text: 'hello',
            attributes: [signature: 'abc']
        )
        DeviceCommandSignatureVerification missing = service.verifyDetailed(missingTimestamp)
        assertFalse(missing.valid)
        assertEquals('Missing command timestamp', missing.reason)

        DeviceCommandMessage invalidTimestamp = new DeviceCommandMessage(
            commandId: 'command-invalid-time',
            taskId: 'task-invalid-time',
            userId: 'user-1',
            deviceId: 'device-1',
            type: DeviceProtocol.TYPE_NATURAL_LANGUAGE_TASK,
            text: 'hello',
            createdAt: 'not-a-time',
            attributes: [signature: 'abc']
        )
        DeviceCommandSignatureVerification invalid = service.verifyDetailed(invalidTimestamp)
        assertFalse(invalid.valid)
        assertEquals('Invalid command timestamp', invalid.reason)
    }

    @Test
    void rejectsReplayWithinSignatureWindow() {
        EvoForgeProperties properties = new EvoForgeProperties()
        properties.deviceAgent.commandSigningSecret = 'unit-test-secret'
        properties.deviceAgent.commandSignatureTtlSeconds = 60
        DeviceCommandSignatureService service = newService(properties)
        DeviceCommandMessage command = signedCommand(service, Instant.now())

        assertTrue(service.verifyDetailed(command).valid)
        DeviceCommandSignatureVerification replay = service.verifyDetailed(command)

        assertFalse(replay.valid)
        assertEquals('Replay command signature', replay.reason)
    }

    @Test
    void rejectsMissingCommandIdWhenSigningEnabled() {
        EvoForgeProperties properties = new EvoForgeProperties()
        properties.deviceAgent.commandSigningSecret = 'unit-test-secret'
        DeviceCommandSignatureService service = newService(properties)

        DeviceCommandMessage command = new DeviceCommandMessage(
            taskId: 'task-no-command-id',
            userId: 'user-1',
            deviceId: 'device-1',
            type: DeviceProtocol.TYPE_NATURAL_LANGUAGE_TASK,
            text: 'hello',
            createdAt: Instant.now().toString(),
            attributes: [signature: 'abc']
        )
        DeviceCommandSignatureVerification verification = service.verifyDetailed(command)

        assertFalse(verification.valid)
        assertEquals('Missing command id', verification.reason)
    }

    @Test
    void rejectsReplayAcrossServiceInstancesSharingStore() {
        EvoForgeProperties properties = new EvoForgeProperties()
        properties.deviceAgent.commandSigningSecret = 'unit-test-secret'
        properties.deviceAgent.commandSignatureTtlSeconds = 60
        DeviceCommandReplayStore replayStore = new InMemoryDeviceCommandReplayStore()
        DeviceCommandSignatureService signer = newService(properties, replayStore)
        DeviceCommandSignatureService verifierAfterRestart = newService(properties, replayStore)
        DeviceCommandMessage command = signedCommand(signer, Instant.now())

        assertTrue(signer.verifyDetailed(command).valid)
        DeviceCommandSignatureVerification replay = verifierAfterRestart.verifyDetailed(command)

        assertFalse(replay.valid)
        assertEquals('Replay command signature', replay.reason)
    }

    private static DeviceCommandMessage signedCommand(DeviceCommandSignatureService service, Instant createdAt) {
        DeviceCommandMessage command = new DeviceCommandMessage(
            commandId: "command-${createdAt.epochSecond}".toString(),
            taskId: "task-${createdAt.epochSecond}".toString(),
            userId: 'user-1',
            deviceId: 'device-1',
            type: DeviceProtocol.TYPE_NATURAL_LANGUAGE_TASK,
            text: 'hello',
            createdAt: createdAt.toString(),
            attributes: [foo: 'bar']
        )
        service.sign(command)
        return command
    }

    private static DeviceCommandMessage copyCommand(DeviceCommandMessage source) {
        return new DeviceCommandMessage(
            commandId: source.commandId,
            taskId: source.taskId,
            userId: source.userId,
            deviceId: source.deviceId,
            type: source.type,
            text: source.text,
            skillId: source.skillId,
            llm: source.llm,
            codeModel: source.codeModel,
            requiresApproval: source.requiresApproval,
            createdAt: source.createdAt,
            attributes: new LinkedHashMap(source.attributes)
        )
    }

    private static DeviceCommandSignatureService newService() {
        EvoForgeProperties properties = new EvoForgeProperties()
        properties.deviceAgent.commandSigningSecret = 'unit-test-secret'
        return newService(properties)
    }

    private static DeviceCommandSignatureService newService(EvoForgeProperties properties) {
        return newService(properties, new InMemoryDeviceCommandReplayStore())
    }

    private static DeviceCommandSignatureService newService(
        EvoForgeProperties properties,
        DeviceCommandReplayStore replayStore
    ) {
        return new DeviceCommandSignatureService(properties, replayStore)
    }
}
