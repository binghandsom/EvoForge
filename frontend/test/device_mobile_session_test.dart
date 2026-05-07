import 'package:evoforge_web/shared/messaging/device_command_factory.dart';
import 'package:evoforge_web/shared/messaging/device_mobile_session.dart';
import 'package:evoforge_web/shared/models/device_task_event.dart';
import 'package:evoforge_web/shared/security/device_event_verifier.dart';
import 'package:evoforge_web/shared/security/device_command_signer.dart';
import 'package:flutter_test/flutter_test.dart';

void main() {
  test('creates commands and aggregates returned events', () {
    var nextId = 0;
    final session = DeviceMobileSession(
      commandFactory: DeviceCommandFactory(
        userId: 'user-1',
        deviceId: 'pc-1',
        signer: DeviceCommandSigner(secret: ''),
        clock: () => DateTime.parse('2026-05-07T00:00:00Z'),
        idFactory: () => 'id-${++nextId}',
      ),
    );

    final command = session.sendNaturalLanguageTask(text: 'hello');
    final taskId = command.taskId;

    session.receiveEventJson({
      'eventId': 'event-1',
      'taskId': taskId,
      'userId': 'user-1',
      'deviceId': 'pc-1',
      'type': 'queued',
      'status': 'queued',
      'level': 'info',
      'message': 'queued',
      'recoverable': false,
      'payload': command.payload,
      'createdAt': '2026-05-07T00:00:01Z',
    });
    session.receiveEventJson({
      'eventId': 'event-2',
      'taskId': taskId,
      'userId': 'user-1',
      'deviceId': 'pc-1',
      'type': 'needs_approval',
      'status': 'needs_approval',
      'level': 'warn',
      'message': 'needs approval',
      'recoverable': true,
      'payload': command.payload,
      'createdAt': '2026-05-07T00:00:02Z',
    });

    expect(session.recentTasks(), hasLength(1));
    expect(session.recentTasks().first.commandText, 'hello');
    expect(session.needsApproval(taskId), isTrue);
    expect(session.eventsForTask(taskId).map((event) => event.eventId),
        ['event-1', 'event-2']);

    final approval = session.approveTask(taskId, note: 'go');
    expect(approval.payload['taskId'], taskId);
    expect((approval.payload['attributes'] as Map)['decision'], 'approve');
    expect((approval.payload['attributes'] as Map)['note'], 'go');
  });

  test('updates latest status from heartbeat json', () {
    final session = DeviceMobileSession(
      commandFactory: DeviceCommandFactory(
        userId: 'user-1',
        deviceId: 'pc-1',
        signer: DeviceCommandSigner(secret: ''),
      ),
      eventSigningSecret: '',
    );

    session.receiveEventJson({
      'eventId': 'heartbeat-1',
      'taskId': 'heartbeat-pc-1',
      'userId': 'user-1',
      'deviceId': 'pc-1',
      'type': 'heartbeat',
      'status': 'online',
      'level': 'info',
      'payload': {
        'enabled': true,
        'userId': 'user-1',
        'deviceId': 'pc-1',
        'commandExchange': 'evoforge.commands',
        'eventExchange': 'evoforge.events',
        'commandQueue': 'evoforge.device.pc-1.commands',
        'eventQueue': 'evoforge.device.pc-1.events',
        'commandRoutingKey': 'user.user-1.device.pc-1.command',
        'eventRoutingKey': 'user.user-1.device.pc-1.event',
        'heartbeatSeconds': 30,
        'commandTypes': ['natural_language_task'],
        'commandSigning': {
          'enabled': false,
          'ttlSeconds': 300,
          'replayStore': 'memory',
          'persistentReplayProtection': false,
        },
        'eventSigning': {'enabled': false},
        'codexTask': {
          'enabled': false,
          'requiresApproval': true,
          'defaultWorkspace': 'evoforge',
          'workingDirectory': '.',
          'workspaces': [
            {'key': 'evoforge', 'path': '/projects/evoforge'}
          ],
          'timeoutSeconds': 600,
        },
        'capabilities': ['approval_requests'],
      },
      'createdAt': '2026-05-07T00:00:00Z',
    });

    expect(session.latestStatus?.deviceId, 'pc-1');
    expect(session.latestStatus?.commandRoutingKey,
        'user.user-1.device.pc-1.command');
    expect(session.latestStatus?.codexTask.defaultWorkspace, 'evoforge');
    expect(session.latestStatus?.codexTask.workspaces.first.key, 'evoforge');
  });

  test('rejects unsigned or tampered events when event signing is enabled', () {
    final session = DeviceMobileSession(
      commandFactory: DeviceCommandFactory(
        userId: 'user-1',
        deviceId: 'pc-1',
        signer: DeviceCommandSigner(secret: ''),
      ),
      eventSigningSecret: 'event-secret',
    );
    final verifier = DeviceEventVerifier(secret: 'event-secret');
    final valid = _event(message: 'ok');
    valid.payload[DeviceEventVerifier.signatureAttribute] =
        verifier.signatureFor(valid);

    expect(session.receiveEvent(_event(message: 'unsigned')), isFalse);
    expect(session.recentTasks(), isEmpty);
    expect(session.receiveEvent(valid), isTrue);
    expect(session.recentTasks(), hasLength(1));

    final tampered = _event(message: 'changed');
    tampered.payload[DeviceEventVerifier.signatureAttribute] =
        valid.payload[DeviceEventVerifier.signatureAttribute];
    expect(session.receiveEvent(tampered), isFalse);
    expect(session.eventsForTask('task-signed').map((event) => event.message),
        ['ok']);
  });
}

DeviceTaskEvent _event({required String message}) {
  return DeviceTaskEvent(
    eventId: 'event-$message',
    taskId: 'task-signed',
    userId: 'user-1',
    deviceId: 'pc-1',
    type: 'completed',
    status: 'completed',
    level: 'info',
    message: message,
    output: '',
    recoverable: false,
    payload: {'text': 'signed task'},
    createdAt: '2026-05-07T00:00:00Z',
  );
}
