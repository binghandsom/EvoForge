import 'package:evoforge_web/shared/messaging/device_event_inbox.dart';
import 'package:evoforge_web/shared/models/device_task_event.dart';
import 'package:flutter_test/flutter_test.dart';

void main() {
  test('deduplicates events and builds recent task summary', () {
    final inbox = DeviceEventInbox()
      ..add(_event(
        eventId: 'e2',
        taskId: 'task-1',
        status: 'completed',
        type: 'completed',
        createdAt: '2026-05-07T00:00:02Z',
        output: 'done',
      ))
      ..add(_event(
        eventId: 'e1',
        taskId: 'task-1',
        status: 'queued',
        type: 'queued',
        createdAt: '2026-05-07T00:00:01Z',
        payload: {
          'commandType': 'natural_language_task',
          'text': 'hello',
        },
      ))
      ..add(_event(
        eventId: 'e2',
        taskId: 'task-1',
        status: 'completed',
        type: 'completed',
        createdAt: '2026-05-07T00:00:02Z',
      ));

    final events = inbox.eventsForTask('task-1');
    final tasks = inbox.recentTasks();

    expect(events.map((e) => e.eventId), ['e1', 'e2']);
    expect(tasks, hasLength(1));
    expect(tasks.first.taskId, 'task-1');
    expect(tasks.first.type, 'natural_language_task');
    expect(tasks.first.commandText, 'hello');
    expect(tasks.first.status, 'completed');
    expect(tasks.first.eventCount, 2);
  });

  test('captures heartbeat status snapshot', () {
    final inbox = DeviceEventInbox()
      ..add(_event(
        eventId: 'heartbeat-1',
        taskId: 'heartbeat-pc-1',
        type: 'heartbeat',
        status: 'online',
        payload: {
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
            'enabled': true,
            'ttlSeconds': 300,
            'replayStore': 'postgres',
            'persistentReplayProtection': true,
          },
          'eventSigning': {'enabled': true},
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
      ));

    expect(inbox.latestStatus?.deviceId, 'pc-1');
    expect(inbox.latestStatus?.commandSigningEnabled, isTrue);
    expect(inbox.latestStatus?.eventSigningEnabled, isTrue);
    expect(inbox.latestStatus?.persistentReplayProtection, isTrue);
    expect(inbox.latestStatus?.codexTask.defaultWorkspace, 'evoforge');
    expect(inbox.latestStatus?.codexTask.workspaces.first.key, 'evoforge');
    expect(inbox.recentTasks(), isEmpty);
  });
}

DeviceTaskEvent _event({
  required String eventId,
  required String taskId,
  required String type,
  required String status,
  String createdAt = '2026-05-07T00:00:00Z',
  String output = '',
  Map<String, dynamic> payload = const {},
}) {
  return DeviceTaskEvent(
    eventId: eventId,
    taskId: taskId,
    userId: 'user-1',
    deviceId: 'pc-1',
    type: type,
    status: status,
    level: 'info',
    message: '',
    output: output,
    recoverable: false,
    payload: payload,
    createdAt: createdAt,
  );
}
