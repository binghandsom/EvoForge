import 'dart:async';
import 'dart:convert';

import 'package:evoforge_web/shared/messaging/device_command_factory.dart';
import 'package:evoforge_web/shared/messaging/device_rabbitmq_web_stomp_transport.dart';
import 'package:evoforge_web/shared/messaging/device_socket_client.dart';
import 'package:evoforge_web/shared/security/device_command_signer.dart';
import 'package:flutter_test/flutter_test.dart';

void main() {
  test('builds RabbitMQ STOMP destinations', () {
    expect(
      DeviceStompDestination.exchange(
          'evoforge.commands', 'user.u.device.d.command'),
      '/exchange/evoforge.commands/user.u.device.d.command',
    );
    expect(
      DeviceStompDestination.amqQueue('evoforge.device.pc-1.events'),
      '/amq/queue/evoforge.device.pc-1.events',
    );
  });

  test('encodes and decodes STOMP frames', () {
    final encoded = DeviceStompCodec.encode(const DeviceStompFrame(
      command: 'SEND',
      headers: {
        'destination': '/exchange/evoforge.commands/user.u.device.d.command',
        'custom:header': 'line\nvalue',
        'literal': r'\n',
      },
      body: '{"ok":true}',
    ));
    final decoded = DeviceStompCodec.decode(
      encoded.substring(0, encoded.length - 1),
    );

    expect(decoded.command, 'SEND');
    expect(decoded.headers['destination'],
        '/exchange/evoforge.commands/user.u.device.d.command');
    expect(decoded.headers['custom:header'], 'line\nvalue');
    expect(decoded.headers['literal'], r'\n');
    expect(decoded.body, '{"ok":true}');
  });

  test('connects, subscribes, publishes commands, and receives events',
      () async {
    final socket = FakeDeviceSocketClient();
    final transport = DeviceRabbitMqWebStompTransport(
      socket: socket,
      login: 'mobile-user',
      passcode: 'secret',
      eventDestination: DeviceStompDestination.exchange(
        'evoforge.events',
        'user.user-1.device.pc-1.event',
      ),
    );
    await pumpEventQueue();

    expect(_sentCommands(socket), ['CONNECT']);
    socket.emit(DeviceStompCodec.encode(const DeviceStompFrame(
      command: 'CONNECTED',
      headers: {'version': '1.2'},
    )));
    await transport.connected;
    await pumpEventQueue();
    expect(_sentCommands(socket), ['CONNECT', 'SUBSCRIBE']);

    final subscribe = _decodeSent(socket.sent[1]);
    expect(subscribe.headers['destination'],
        '/exchange/evoforge.events/user.user-1.device.pc-1.event');
    expect(subscribe.headers['ack'], 'auto');

    final envelope = _factory().codexTask(text: 'inspect project');
    await transport.publish(envelope);
    final send = _decodeSent(socket.sent[2]);
    expect(send.command, 'SEND');
    expect(send.headers['destination'],
        '/exchange/evoforge.commands/user.user-1.device.pc-1.command');
    expect(jsonDecode(send.body)['taskId'], envelope.taskId);

    final received = <String>[];
    final subscription = transport.events.listen(
      (event) => received.add(event.eventId),
    );
    socket.emit(DeviceStompCodec.encode(DeviceStompFrame(
      command: 'MESSAGE',
      headers: {'subscription': 'evoforge-mobile-events'},
      body: jsonEncode(_event('event-1')),
    )));
    await pumpEventQueue();

    expect(received, ['event-1']);

    await subscription.cancel();
    await transport.close();
  });

  test('parses frames split across socket chunks', () {
    final parser = DeviceStompFrameParser();
    expect(parser.add('MESSAGE\n\nhel'), isEmpty);
    final frames = parser.add('lo\u0000\nMESSAGE\n\nagain\u0000');

    expect(frames, hasLength(2));
    expect(frames.first.body, 'hello');
    expect(frames.last.body, 'again');
  });

  test('parses binary socket chunks', () {
    final parser = DeviceStompFrameParser();
    final frames = parser.add(utf8.encode('MESSAGE\n\nhello\u0000'));

    expect(frames, hasLength(1));
    expect(frames.first.body, 'hello');
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

List<String> _sentCommands(FakeDeviceSocketClient socket) {
  return socket.sent.map((message) => _decodeSent(message).command).toList();
}

DeviceStompFrame _decodeSent(Object? message) {
  final text = message.toString();
  return DeviceStompCodec.decode(text.substring(0, text.length - 1));
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
