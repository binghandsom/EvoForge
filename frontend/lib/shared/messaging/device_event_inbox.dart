import '../models/device_protocol.dart';
import '../models/device_status.dart';
import '../models/device_task_event.dart';
import '../models/device_task_summary.dart';
import '../security/device_event_verifier.dart';

class DeviceEventInbox {
  final Map<String, DeviceTaskEvent> _eventsById = {};
  final DeviceEventVerifier? verifier;
  DeviceStatus? latestStatus;

  DeviceEventInbox({this.verifier});

  bool add(DeviceTaskEvent event) {
    if (verifier != null && !verifier!.verify(event)) {
      return false;
    }
    if (event.eventId.isNotEmpty) {
      _eventsById[event.eventId] = event;
    } else {
      _eventsById['${event.taskId}:${event.type}:${event.createdAt}'] = event;
    }
    if (event.type == 'heartbeat' && event.payload.isNotEmpty) {
      latestStatus = DeviceStatus.fromJson(event.payload);
    }
    return true;
  }

  void addAll(Iterable<DeviceTaskEvent> events) {
    for (final event in events) {
      add(event);
    }
  }

  List<DeviceTaskEvent> eventsForTask(String taskId) {
    final events = _eventsById.values
        .where((event) => event.taskId == taskId)
        .toList()
      ..sort(_compareEvents);
    return events;
  }

  List<DeviceTaskSummary> recentTasks({int limit = 50}) {
    final grouped = <String, List<DeviceTaskEvent>>{};
    for (final event in _eventsById.values) {
      if (event.taskId.isEmpty || event.type == 'heartbeat') continue;
      grouped.putIfAbsent(event.taskId, () => []).add(event);
    }

    final summaries = grouped.entries.map((entry) {
      final events = entry.value..sort(_compareEvents);
      final first = events.first;
      final latest = events.last;
      return DeviceTaskSummary(
        taskId: entry.key,
        userId: latest.userId,
        deviceId: latest.deviceId,
        type: _text(first.payload['commandType']) ??
            _text(latest.payload['commandType']) ??
            latest.type,
        status: latest.status,
        level: latest.level,
        message: latest.message,
        commandText:
            _text(first.payload['text']) ?? _text(latest.payload['text']) ?? '',
        recoverable: latest.recoverable,
        eventCount: events.length,
        firstEventAt: first.createdAt,
        lastEventAt: latest.createdAt,
      );
    }).toList()
      ..sort((a, b) => b.lastEventAt.compareTo(a.lastEventAt));

    return summaries.take(limit).toList();
  }

  bool needsApproval(String taskId) {
    final events = eventsForTask(taskId);
    return events.isNotEmpty &&
        DeviceTaskStatus.needsApprovalNow(events.last.status);
  }

  static int _compareEvents(DeviceTaskEvent a, DeviceTaskEvent b) {
    final byTime = a.createdAt.compareTo(b.createdAt);
    if (byTime != 0) return byTime;
    return a.eventId.compareTo(b.eventId);
  }

  static String? _text(Object? value) {
    if (value == null) return null;
    final text = value.toString();
    return text.isEmpty ? null : text;
  }
}
