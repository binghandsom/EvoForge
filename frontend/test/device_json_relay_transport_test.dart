import 'dart:async';
import 'dart:convert';

import 'package:evoforge_web/shared/messaging/device_command_factory.dart';
import 'package:evoforge_web/shared/messaging/device_json_relay_transport.dart';
import 'package:evoforge_web/shared/messaging/device_socket_client.dart';
import 'package:evoforge_web/shared/security/device_command_signer.dart';
import 'package:flutter_test/flutter_test.dart';

void main() {
  test('publishes command envelopes as JSON relay messages', () async {
    final socket = FakeDeviceSocketClient();
    final transport = DeviceJsonRelayTransport(client: socket);
    final envelope = _factory().naturalLanguageTask(text: 'hello');

    await transport.publish(envelope);

    final published =
        jsonDecode(socket.sent.single as String) as Map<String, dynamic>;
    expect(published['type'], 'device_command');
    expect(published['exchange'], 'evoforge.commands');
    expect(published['routingKey'], 'user.user-1.device.pc-1.command');
    expect(published['payload']['taskId'], envelope.taskId);

    await transport.close();
  });

  test('decodes direct, wrapped, and batched event messages', () async {
    final socket = FakeDeviceSocketClient();
    final transport = DeviceJsonRelayTransport(client: socket);
    final events = <String>[];
    final subscription = transport.events.listen(
      (event) => events.add(event.eventId),
    );

    socket.emit(jsonEncode(_event('event-1')));
    socket.emit(
        jsonEncode({'type': 'device_event', 'payload': _event('event-2')}));
    socket.emit(jsonEncode({
      'events': [_event('event-3'), _event('event-4')]
    }));
    await pumpEventQueue();

    expect(events, ['event-1', 'event-2', 'event-3', 'event-4']);

    await subscription.cancel();
    await transport.close();
  });

  test('decodes binary websocket messages', () async {
    final socket = FakeDeviceSocketClient();
    final transport = DeviceJsonRelayTransport(client: socket);
    final events = <String>[];
    final subscription = transport.events.listen(
      (event) => events.add(event.eventId),
    );

    socket.emit(utf8.encode(jsonEncode(_event('event-bytes'))));
    await pumpEventQueue();

    expect(events, ['event-bytes']);

    await subscription.cancel();
    await transport.close();
  });

  test('surfaces malformed relay messages without closing event stream',
      () async {
    final socket = FakeDeviceSocketClient();
    final transport = DeviceJsonRelayTransport(client: socket);
    final errors = <Object>[];
    final errorSubscription = transport.malformedMessages.listen(errors.add);
    final events = <String>[];
    final eventSubscription =
        transport.events.listen((event) => events.add(event.eventId));

    socket.emit(jsonEncode({'type': 'unknown'}));
    socket.emit(jsonEncode(_event('event-ok')));
    await pumpEventQueue();

    expect(errors, hasLength(1));
    expect(events, ['event-ok']);

    await eventSubscription.cancel();
    await errorSubscription.cancel();
    await transport.close();
  });
}

DeviceCommandFactory _factory() {
  return DeviceCommandFactory(
    userId: 'user-1',
    deviceId: 'pc-1',
    signer: DeviceCommandSigner(secret: ''),
    clock: () => DateTime.parse('2026-05-07T00:00:00Z'),
    idFactory: () => 'fixed-id',
  );
}

Map<String, Object?> _event(String eventId) {
  return {
    'eventId': eventId,
    'taskId': 'task-1',
    'userId': 'user-1',
    'deviceId': 'pc-1',
    'type': 'completed',
    'status': 'completed',
    'level': 'info',
    'message': 'done',
    'output': '',
    'recoverable': false,
    'payload': {'text': 'hello'},
    'createdAt': '2026-05-07T00:00:00Z',
  };
}

class FakeDeviceSocketClient implements DeviceSocketClient {
  final StreamController<Object?> _messages =
      StreamController<Object?>.broadcast();
  final List<Object?> sent = [];

  @override
  Stream<Object?> get messages => _messages.stream;

  @override
  Future<void> send(Object? message) async {
    sent.add(message);
  }

  void emit(Object? message) {
    _messages.add(message);
  }

  @override
  Future<void> close() {
    return _messages.close();
  }
}
