import 'package:evoforge_web/shared/messaging/device_command_factory.dart';
import 'package:evoforge_web/shared/models/device_protocol.dart';
import 'package:evoforge_web/shared/security/device_command_signer.dart';
import 'package:flutter_test/flutter_test.dart';

void main() {
  test('builds signed natural language command envelope', () {
    var nextId = 0;
    final factory = DeviceCommandFactory(
      userId: 'user-1',
      deviceId: 'pc-1',
      signer: DeviceCommandSigner(secret: 'unit-test-secret'),
      clock: () => DateTime.parse('2026-05-07T00:00:00Z'),
      idFactory: () => 'id-${++nextId}',
    );

    final envelope = factory.naturalLanguageTask(
      text: 'do it',
      requiresApproval: true,
      attributes: {'priority': 'high'},
    );

    expect(envelope.exchange, 'evoforge.commands');
    expect(envelope.routingKey, 'user.user-1.device.pc-1.command');
    expect(envelope.toPublishRequest()['exchange'], 'evoforge.commands');
    expect(envelope.toPublishRequest()['routingKey'],
        'user.user-1.device.pc-1.command');
    expect(envelope.commandId, 'id-1');
    expect(envelope.taskId, 'id-2');
    expect(envelope.type, DeviceCommandType.naturalLanguageTask);
    expect(envelope.payload['commandId'], 'id-1');
    expect(envelope.payload['taskId'], 'id-2');
    expect(envelope.payload['type'], DeviceCommandType.naturalLanguageTask);
    expect(envelope.payload['createdAt'], '2026-05-07T00:00:00.000Z');
    expect((envelope.payload['attributes'] as Map)['priority'], 'high');
    expect((envelope.payload['attributes'] as Map)['signature'], isNotEmpty);
  });

  test('builds approval decision with original task id', () {
    final factory = DeviceCommandFactory(
      userId: 'user-1',
      deviceId: 'pc-1',
      signer: DeviceCommandSigner(secret: ''),
      clock: () => DateTime.parse('2026-05-07T00:00:00Z'),
      idFactory: () => 'command-id',
    );

    final envelope = factory.approvalDecision(
      taskId: 'task-waiting',
      decision: DeviceApprovalDecision.approve,
      note: 'ok',
    );

    expect(envelope.payload['taskId'], 'task-waiting');
    expect(envelope.payload['commandId'], 'command-id');
    expect(envelope.payload['type'], DeviceCommandType.approvalDecision);
    expect(envelope.payload['text'], DeviceApprovalDecision.approve);
    expect((envelope.payload['attributes'] as Map)['decision'], 'approve');
    expect((envelope.payload['attributes'] as Map)['actor'], 'mobile');
    expect((envelope.payload['attributes'] as Map)['note'], 'ok');
    expect((envelope.payload['attributes'] as Map).containsKey('signature'),
        isFalse);
  });

  test('builds client request envelope for message bus data fetches', () {
    final factory = DeviceCommandFactory(
      userId: 'user-1',
      deviceId: 'pc-1',
      signer: DeviceCommandSigner(secret: ''),
      clock: () => DateTime.parse('2026-05-07T00:00:00Z'),
      idFactory: () => 'request-id',
    );

    final envelope = factory.clientRequest(
      method: 'agent.conversations.list',
      params: {'limit': 20},
    );

    expect(envelope.type, DeviceCommandType.clientRequest);
    expect(envelope.taskId, 'request-id');
    final attributes = envelope.payload['attributes'] as Map;
    final request = attributes['request'] as Map;
    expect(request['requestId'], 'request-id');
    expect(request['method'], 'agent.conversations.list');
    expect((request['params'] as Map)['limit'], 20);
  });
}
