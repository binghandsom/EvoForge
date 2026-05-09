import 'dart:async';

import 'package:evoforge_web/shared/messaging/device_command_factory.dart';
import 'package:evoforge_web/shared/messaging/device_message_transport.dart';
import 'package:evoforge_web/shared/messaging/device_reconnecting_message_transport.dart';
import 'package:evoforge_web/shared/models/device_task_event.dart';
import 'package:evoforge_web/shared/security/device_command_signer.dart';
import 'package:flutter_test/flutter_test.dart';

void main() {
  test('reconnects after the inner transport stream closes', () async {
    final transports = <FakeDeviceMessageTransport>[];
    final reconnecting = DeviceReconnectingMessageTransport(
      initialDelay: Duration.zero,
      maxDelay: Duration.zero,
      connector: () {
        final transport = FakeDeviceMessageTransport();
        transports.add(transport);
        return transport;
      },
    );
    await pumpEventQueue();
    expect(transports, hasLength(1));

    transports.first.closeEvents();
    await pumpEventQueue(times: 4);
    expect(transports, hasLength(2));

    final envelope = _factory().naturalLanguageTask(text: 'hello');
    await reconnecting.publish(envelope);
    expect(transports.last.published.single.taskId, envelope.taskId);

    await reconnecting.close();
  });

  test('keeps the public event stream open across reconnects', () async {
    final transports = <FakeDeviceMessageTransport>[];
    final reconnecting = DeviceReconnectingMessageTransport(
      initialDelay: Duration.zero,
      maxDelay: Duration.zero,
      connector: () {
        final transport = FakeDeviceMessageTransport();
        transports.add(transport);
        return transport;
      },
    );
    final received = <String>[];
    final subscription = reconnecting.events.listen(
      (event) => received.add(event.eventId),
    );
    await pumpEventQueue();

    transports.first.emit(_event('event-1'));
    transports.first.closeEvents();
    await pumpEventQueue(times: 4);
    transports.last.emit(_event('event-2'));
    await pumpEventQueue();

    expect(received, ['event-1', 'event-2']);

    await subscription.cancel();
    await reconnecting.close();
  });
}

DeviceCommandFactory _factory() {
  return DeviceCommandFactory(
    userId: 'user-1',
    deviceId: 'pc-1',
    signer: DeviceCommandSigner(secret: ''),
    clock: () => DateTime.parse('2026-05-08T00:00:00Z'),
    idFactory: () => 'fixed-id',
  );
}

DeviceTaskEvent _event(String eventId) {
  return DeviceTaskEvent(
    eventId: eventId,
    taskId: 'task-1',
    userId: 'user-1',
    deviceId: 'pc-1',
    type: 'completed',
    status: 'completed',
    level: 'info',
    message: 'done',
    output: '',
    recoverable: false,
    payload: const {},
    createdAt: '2026-05-08T00:00:00Z',
  );
}

class FakeDeviceMessageTransport implements DeviceMessageTransport {
  final StreamController<DeviceTaskEvent> _events =
      StreamController<DeviceTaskEvent>.broadcast();
  final List<DeviceCommandEnvelope> published = [];

  @override
  Stream<DeviceTaskEvent> get events => _events.stream;

  @override
  Future<void> publish(DeviceCommandEnvelope envelope) async {
    published.add(envelope);
  }

  void emit(DeviceTaskEvent event) {
    _events.add(event);
  }

  Future<void> closeEvents() {
    return _events.close();
  }

  Future<void> close() {
    if (_events.isClosed) return Future.value();
    return _events.close();
  }
}
