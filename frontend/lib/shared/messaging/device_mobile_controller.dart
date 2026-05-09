import 'dart:async';

import '../models/device_status.dart';
import '../models/device_task_event.dart';
import '../models/device_task_summary.dart';
import 'device_command_factory.dart';
import 'device_message_transport.dart';
import 'device_mobile_session.dart';

class DeviceMobileController {
  final DeviceMobileSession session;
  final DeviceMessageTransport transport;
  StreamSubscription<DeviceTaskEvent>? _subscription;

  DeviceMobileController({
    required this.session,
    required this.transport,
  });

  DeviceStatus? get latestStatus => session.latestStatus;

  Future<void> start() async {
    await stop();
    _subscription = transport.events.listen((event) {
      session.receiveEvent(event);
    });
  }

  Future<void> stop() async {
    await _subscription?.cancel();
    _subscription = null;
  }

  Future<DeviceCommandEnvelope> sendNaturalLanguageTask({
    required String text,
    bool requiresApproval = false,
    String? skillId,
    String? llm,
    String? codeModel,
    Map<String, Object?> attributes = const {},
  }) async {
    final envelope = session.sendNaturalLanguageTask(
      text: text,
      requiresApproval: requiresApproval,
      skillId: skillId,
      llm: llm,
      codeModel: codeModel,
      attributes: attributes,
    );
    await transport.publish(envelope);
    return envelope;
  }

  Future<DeviceCommandEnvelope> sendCodexTask({
    required String text,
    bool requiresApproval = true,
    Map<String, Object?> attributes = const {},
  }) async {
    final envelope = session.sendCodexTask(
      text: text,
      requiresApproval: requiresApproval,
      attributes: attributes,
    );
    await transport.publish(envelope);
    return envelope;
  }

  Future<DeviceCommandEnvelope> approveTask(String taskId, {String? note}) {
    return _publish(session.approveTask(taskId, note: note));
  }

  Future<DeviceCommandEnvelope> rejectTask(String taskId, {String? note}) {
    return _publish(session.rejectTask(taskId, note: note));
  }

  Future<DeviceCommandEnvelope> answerCodexQuestion({
    required String questionId,
    required String answer,
    String? questionTaskId,
    String actor = 'mobile',
    String? note,
  }) {
    return _publish(
      session.answerCodexQuestion(
        questionId: questionId,
        answer: answer,
        questionTaskId: questionTaskId,
        actor: actor,
        note: note,
      ),
    );
  }

  List<DeviceTaskSummary> recentTasks({int limit = 50}) {
    return session.recentTasks(limit: limit);
  }

  List<DeviceTaskEvent> eventsForTask(String taskId) {
    return session.eventsForTask(taskId);
  }

  bool needsApproval(String taskId) {
    return session.needsApproval(taskId);
  }

  Future<DeviceCommandEnvelope> _publish(DeviceCommandEnvelope envelope) async {
    await transport.publish(envelope);
    return envelope;
  }
}
