import 'dart:async';

import 'package:evoforge_web/shared/messaging/device_command_factory.dart';
import 'package:evoforge_web/shared/messaging/device_message_transport.dart';
import 'package:evoforge_web/shared/messaging/device_mobile_connection_config.dart';
import 'package:evoforge_web/shared/messaging/device_mobile_controller_factory.dart';
import 'package:evoforge_web/shared/models/device_task_event.dart';
import 'package:flutter_test/flutter_test.dart';

void main() {
  test('creates controller with configured session and injected transport',
      () async {
    final transport = FakeDeviceMessageTransport();
    final config = DeviceMobileConnectionConfig.jsonRelay(
      uri: Uri.parse('wss://relay.example.com/evoforge/device'),
      userId: 'user-1',
      deviceId: 'pc-1',
      commandExchange: 'evoforge.commands',
      commandRoutingKey: 'user.user-1.device.pc-1.command',
      eventExchange: 'evoforge.events',
      eventRoutingKey: 'user.user-1.device.pc-1.event',
      eventQueue: 'evoforge.device.pc-1.events',
      commandSigningSecret: 'command-secret',
      eventSigningSecret: '',
    );
    final factory = DeviceMobileControllerFactory(
      transportBuilder: (_) => transport,
    );

    final controller = factory.create(config);
    final command = await controller.sendNaturalLanguageTask(text: 'hello');

    expect(command.payload['userId'], 'user-1');
    expect(command.payload['deviceId'], 'pc-1');
    expect(command.exchange, 'evoforge.commands');
    expect(command.routingKey, 'user.user-1.device.pc-1.command');
    expect(
      (command.payload['attributes'] as Map<String, Object?>)['signature'],
      isA<String>(),
    );
    expect(transport.published.single.taskId, command.taskId);

    await transport.close();
  });
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

  Future<void> close() {
    return _events.close();
  }
}
