import 'package:evoforge_web/shared/models/device_task_event.dart';
import 'package:evoforge_web/shared/security/device_event_verifier.dart';
import 'package:flutter_test/flutter_test.dart';

void main() {
  test('verifies event signature and rejects tampering', () {
    final verifier = DeviceEventVerifier(secret: 'unit-test-secret');
    final event = _event();
    event.payload[DeviceEventVerifier.signatureAttribute] =
        verifier.signatureFor(event);

    expect(verifier.verify(event), isTrue);

    final tampered = _event();
    tampered.payload[DeviceEventVerifier.signatureAttribute] =
        event.payload[DeviceEventVerifier.signatureAttribute];
    final changed = _event(message: 'changed');
    changed.payload[DeviceEventVerifier.signatureAttribute] =
        event.payload[DeviceEventVerifier.signatureAttribute];

    expect(verifier.verify(tampered), isTrue);
    expect(verifier.verify(changed), isFalse);
  });

  test('disabled verifier accepts unsigned events', () {
    expect(DeviceEventVerifier(secret: '').verify(_event()), isTrue);
  });
}

DeviceTaskEvent _event({String message = 'done'}) {
  return DeviceTaskEvent(
    eventId: 'event-1',
    taskId: 'task-1',
    userId: 'user-1',
    deviceId: 'pc-1',
    type: 'completed',
    status: 'completed',
    level: 'info',
    message: message,
    output: 'ok',
    recoverable: false,
    payload: {
      'nested': {'b': 2, 'a': 1},
      'priority': 'high',
    },
    createdAt: '2026-05-07T00:00:00Z',
  );
}
