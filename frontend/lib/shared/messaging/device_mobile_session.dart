import '../models/device_protocol.dart';
import '../models/device_status.dart';
import '../models/device_task_event.dart';
import '../models/device_task_summary.dart';
import '../security/device_event_verifier.dart';
import 'device_command_factory.dart';
import 'device_event_inbox.dart';

class DeviceMobileSession {
  final DeviceCommandFactory commandFactory;
  final DeviceEventInbox inbox;

  DeviceMobileSession({
    required this.commandFactory,
    DeviceEventInbox? inbox,
    String eventSigningSecret = '',
  }) : inbox = inbox ??
            DeviceEventInbox(
              verifier: eventSigningSecret.isEmpty
                  ? null
                  : DeviceEventVerifier(secret: eventSigningSecret),
            );

  DeviceStatus? get latestStatus => inbox.latestStatus;

  List<DeviceTaskSummary> recentTasks({int limit = 50}) {
    return inbox.recentTasks(limit: limit);
  }

  List<DeviceTaskEvent> eventsForTask(String taskId) {
    return inbox.eventsForTask(taskId);
  }

  bool needsApproval(String taskId) {
    return inbox.needsApproval(taskId);
  }

  DeviceCommandEnvelope sendNaturalLanguageTask({
    required String text,
    bool requiresApproval = false,
    String? skillId,
    String? llm,
    String? codeModel,
    Map<String, Object?> attributes = const {},
  }) {
    return commandFactory.naturalLanguageTask(
      text: text,
      requiresApproval: requiresApproval,
      skillId: skillId,
      llm: llm,
      codeModel: codeModel,
      attributes: attributes,
    );
  }

  DeviceCommandEnvelope sendCodexTask({
    required String text,
    bool requiresApproval = true,
    Map<String, Object?> attributes = const {},
  }) {
    return commandFactory.codexTask(
      text: text,
      requiresApproval: requiresApproval,
      attributes: attributes,
    );
  }

  DeviceCommandEnvelope sendTesterTask({
    required String text,
    bool requiresApproval = false,
    Map<String, Object?> attributes = const {},
  }) {
    return commandFactory.testerTask(
      text: text,
      requiresApproval: requiresApproval,
      attributes: attributes,
    );
  }

  DeviceCommandEnvelope approveTask(String taskId, {String? note}) {
    return commandFactory.approvalDecision(
      taskId: taskId,
      decision: DeviceApprovalDecision.approve,
      note: note,
    );
  }

  DeviceCommandEnvelope rejectTask(String taskId, {String? note}) {
    return commandFactory.approvalDecision(
      taskId: taskId,
      decision: DeviceApprovalDecision.reject,
      note: note,
    );
  }

  DeviceCommandEnvelope answerCodexQuestion({
    required String questionId,
    required String answer,
    String? questionTaskId,
    String actor = 'mobile',
    String? note,
  }) {
    return commandFactory.humanResponse(
      questionId: questionId,
      answer: answer,
      questionTaskId: questionTaskId,
      actor: actor,
      note: note,
    );
  }

  DeviceCommandEnvelope sendClientRequest({
    required String method,
    Map<String, Object?> params = const {},
  }) {
    return commandFactory.clientRequest(
      method: method,
      params: params,
    );
  }

  bool receiveEvent(DeviceTaskEvent event) {
    return inbox.add(event);
  }

  bool receiveEventJson(Map<String, dynamic> json) {
    return receiveEvent(DeviceTaskEvent.fromJson(json));
  }

  void receiveEvents(Iterable<DeviceTaskEvent> events) {
    inbox.addAll(events);
  }
}
