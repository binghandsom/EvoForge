import 'package:evoforge_web/shared/security/device_command_signer.dart';
import 'package:flutter_test/flutter_test.dart';

void main() {
  test('builds canonical payload like backend signature service', () {
    final signer = DeviceCommandSigner(secret: 'unit-test-secret');
    final payload = signer.canonicalPayload({
      'commandId': 'command-1',
      'taskId': 'task-1',
      'userId': 'user-1',
      'deviceId': 'device-1',
      'type': 'natural_language_task',
      'text': 'run this',
      'skillId': 'skill-1',
      'llm': 'mock-llm',
      'codeModel': 'mock-code',
      'requiresApproval': true,
      'createdAt': '2026-05-07T00:00:00Z',
      'attributes': {
        'signature': 'old-signature',
        'priority': 'high',
        'nested': {'b': 2, 'a': 1},
      },
    });

    expect(
      payload,
      '{"attributes":{"nested":{"a":1,"b":2},"priority":"high"},"codeModel":"mock-code","commandId":"command-1","createdAt":"2026-05-07T00:00:00Z","deviceId":"device-1","llm":"mock-llm","requiresApproval":true,"skillId":"skill-1","taskId":"task-1","text":"run this","type":"natural_language_task","userId":"user-1"}',
    );
  });

  test('signs command and excludes existing signature from hmac input', () {
    final signer = DeviceCommandSigner(
      secret: 'unit-test-secret',
      clock: () => DateTime.parse('2026-05-07T00:00:00Z'),
      idFactory: () => 'command-1',
    );

    final command = signer.sign({
      'taskId': 'task-1',
      'userId': 'user-1',
      'deviceId': 'device-1',
      'type': 'natural_language_task',
      'text': 'run this',
      'skillId': 'skill-1',
      'llm': 'mock-llm',
      'codeModel': 'mock-code',
      'requiresApproval': true,
      'attributes': {
        'signature': 'transport-copy',
        'priority': 'high',
        'nested': {'b': 2, 'a': 1},
      },
    });

    expect(command['commandId'], 'command-1');
    expect(command['createdAt'], '2026-05-07T00:00:00.000Z');
    expect(
      (command['attributes'] as Map)['signature'],
      '4176e801dcf91c156bfddc84b33f869469be962d1548127d020447c4cefb5f16',
    );
  });
}
