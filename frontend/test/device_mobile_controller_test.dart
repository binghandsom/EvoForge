import 'dart:async';

import 'package:evoforge_web/shared/messaging/device_command_factory.dart';
import 'package:evoforge_web/shared/messaging/device_message_transport.dart';
import 'package:evoforge_web/shared/messaging/device_mobile_controller.dart';
import 'package:evoforge_web/shared/messaging/device_mobile_session.dart';
import 'package:evoforge_web/shared/models/device_task_event.dart';
import 'package:evoforge_web/shared/security/device_command_signer.dart';
import 'package:flutter_test/flutter_test.dart';

void main() {
  test('publishes commands and consumes transport events', () async {
    var nextId = 0;
    final transport = FakeDeviceMessageTransport();
    final controller = DeviceMobileController(
      transport: transport,
      session: DeviceMobileSession(
        commandFactory: DeviceCommandFactory(
          userId: 'user-1',
          deviceId: 'pc-1',
          signer: DeviceCommandSigner(secret: ''),
          clock: () => DateTime.parse('2026-05-07T00:00:00Z'),
          idFactory: () => 'id-${++nextId}',
        ),
      ),
    );

    await controller.start();
    final command = await controller.sendNaturalLanguageTask(
      text: 'hello',
      requiresApproval: true,
    );

    expect(transport.published, hasLength(1));
    expect(transport.published.first.taskId, command.taskId);

    transport.emit(DeviceTaskEvent(
      eventId: 'event-1',
      taskId: command.taskId,
      userId: 'user-1',
      deviceId: 'pc-1',
      type: 'needs_approval',
      status: 'needs_approval',
      level: 'warn',
      message: 'needs approval',
      output: '',
      recoverable: true,
      payload: command.payload.cast<String, dynamic>(),
      createdAt: '2026-05-07T00:00:01Z',
    ));
    await pumpEventQueue();

    expect(controller.needsApproval(command.taskId), isTrue);
    final approval = await controller.approveTask(command.taskId);
    expect(approval.taskId, command.taskId);
    expect(transport.published, hasLength(2));

    await controller.stop();
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

  void emit(DeviceTaskEvent event) {
    _events.add(event);
  }

  Future<void> close() {
    return _events.close();
  }
}
