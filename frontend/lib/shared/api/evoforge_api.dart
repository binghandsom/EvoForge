import 'dart:convert';

import 'package:http/http.dart' as http;

import '../models/device_status.dart';
import '../models/device_task_event.dart';
import '../models/device_task_summary.dart';
import '../models/device_protocol.dart';
import '../models/agent_conversation.dart';
import '../models/model_config.dart';
import '../models/skill.dart';
import '../messaging/evoforge_message_bus_client.dart';

const apiBaseUrl = String.fromEnvironment(
  'API_BASE_URL',
  defaultValue: 'http://localhost:18080',
);

class EvoForgeApi {
  final http.Client _client;
  final EvoForgeMessageBusClient? messageBus;

  EvoForgeApi({http.Client? client, this.messageBus})
      : _client = client ?? http.Client();

  bool get usesMessageBus => messageBus != null;

  Stream<DeviceTaskEvent> get messageBusEvents =>
      messageBus?.events ?? const Stream<DeviceTaskEvent>.empty();

  Future<List<SkillView>> loadSkills() async {
    final response = await _get('/api/skills');
    final data = jsonDecode(response.body) as List<dynamic>;
    return data
        .map((e) => SkillView.fromJson(e as Map<String, dynamic>))
        .toList();
  }

  Future<SkillDetail> loadSkillDetail(String id) async {
    final response = await _get('/api/skills/$id');
    return SkillDetail.fromJson(
      jsonDecode(response.body) as Map<String, dynamic>,
    );
  }

  Future<void> createSkill({
    required String name,
    required String code,
    Map<String, Object?> metadata = const {},
  }) async {
    await _send('POST', '/api/skills', {
      'name': name,
      'code': code,
      'enabled': false,
      'metadata': metadata,
    });
  }

  Future<void> updateSkill(
    String id, {
    required String name,
    required String code,
    Map<String, Object?> metadata = const {},
  }) async {
    await _send('PUT', '/api/skills/$id', {
      'name': name,
      'code': code,
      'metadata': metadata,
    });
  }

  Future<void> activateSkill(String id) async {
    await _send('POST', '/api/skills/$id/activate', {});
  }

  Future<List<dynamic>> loadSkillHistory(String id) async {
    final response = await _get('/api/skills/$id/history');
    return jsonDecode(response.body) as List<dynamic>;
  }

  Future<List<dynamic>> loadSkillAudit(String id) async {
    final response = await _get('/api/audit/$id');
    return jsonDecode(response.body) as List<dynamic>;
  }

  Future<Map<String, dynamic>> executeSkill(
    String id, {
    required String input,
    required bool evaluate,
  }) async {
    final response = await _send('POST', '/api/skills/$id/execute', {
      'input': input,
      'evaluate': evaluate,
    });
    return jsonDecode(response.body) as Map<String, dynamic>;
  }

  Future<Map<String, dynamic>> proposeSkill({
    required String name,
    required String prompt,
  }) async {
    final response = await _send('POST', '/api/skills/propose', {
      'name': name,
      'prompt': prompt,
    });
    return jsonDecode(response.body) as Map<String, dynamic>;
  }

  Future<List<AgentConversationThread>> loadConversations({
    int limit = 50,
  }) async {
    if (messageBus != null) {
      final data = await messageBus!.request(
        'agent.conversations.list',
        params: {'limit': limit},
      );
      return (data as List<dynamic>)
          .map((e) =>
              AgentConversationThread.fromJson(e as Map<String, dynamic>))
          .toList();
    }
    final response = await _get('/api/agent/conversations?limit=$limit');
    final data = jsonDecode(response.body) as List<dynamic>;
    return data
        .map((e) => AgentConversationThread.fromJson(e as Map<String, dynamic>))
        .toList();
  }

  Future<AgentConversationThread> createConversation({
    String? threadId,
    String title = '新对话',
  }) async {
    if (messageBus != null) {
      final data = await messageBus!.request(
        'agent.conversations.create',
        params: {
          if (threadId != null) 'threadId': threadId,
          'title': title,
          'metadata': {'source': 'command-center'},
        },
      );
      return AgentConversationThread.fromJson(data as Map<String, dynamic>);
    }
    final response = await _send('POST', '/api/agent/conversations', {
      if (threadId != null) 'threadId': threadId,
      'title': title,
      'metadata': {'source': 'command-center'},
    });
    return AgentConversationThread.fromJson(
      jsonDecode(response.body) as Map<String, dynamic>,
    );
  }

  Future<List<AgentConversationTurn>> loadConversationTurns(
    String threadId, {
    int limit = 100,
  }) async {
    if (messageBus != null) {
      final data = await messageBus!.request(
        'agent.conversations.turns',
        params: {'threadId': threadId, 'limit': limit},
      );
      return (data as List<dynamic>)
          .map((e) => AgentConversationTurn.fromJson(e as Map<String, dynamic>))
          .toList();
    }
    final response =
        await _get('/api/agent/conversations/$threadId/turns?limit=$limit');
    final data = jsonDecode(response.body) as List<dynamic>;
    return data
        .map((e) => AgentConversationTurn.fromJson(e as Map<String, dynamic>))
        .toList();
  }

  Future<DeviceTaskEvent> dispatchDeviceCommand({
    required String text,
    String type = DeviceCommandType.naturalLanguageTask,
    bool requiresApproval = false,
    Map<String, Object?> attributes = const {},
  }) async {
    if (messageBus != null) {
      final envelope = type == DeviceCommandType.codexTask
          ? messageBus!.commandFactory.codexTask(
              text: text,
              requiresApproval: requiresApproval,
              attributes: attributes,
            )
          : messageBus!.commandFactory.naturalLanguageTask(
              text: text,
              requiresApproval: requiresApproval,
              attributes: attributes,
            );
      await messageBus!.transport.publish(envelope);
      return _localQueuedEvent(envelope);
    }
    final response = await _send('POST', '/api/device/commands', {
      'type': type,
      'text': text,
      'requiresApproval': requiresApproval,
      'attributes': attributes,
    });
    return DeviceTaskEvent.fromJson(
      jsonDecode(response.body) as Map<String, dynamic>,
    );
  }

  Future<DeviceStatus> loadDeviceStatus() async {
    if (messageBus != null) {
      final data = await messageBus!.request('device.status.get');
      return DeviceStatus.fromJson(data as Map<String, dynamic>);
    }
    final response = await _get('/api/device/status');
    return DeviceStatus.fromJson(
      jsonDecode(response.body) as Map<String, dynamic>,
    );
  }

  Future<List<ModelProviderConfigView>> loadModelConfigs() async {
    final response = await _get('/api/models/configs');
    final data = jsonDecode(response.body) as List<dynamic>;
    return data
        .map((e) => ModelProviderConfigView.fromJson(e as Map<String, dynamic>))
        .toList();
  }

  Future<ModelProviderConfigView> createModelConfig(
    Map<String, Object?> payload,
  ) async {
    final response = await _send('POST', '/api/models/configs', payload);
    return ModelProviderConfigView.fromJson(
      jsonDecode(response.body) as Map<String, dynamic>,
    );
  }

  Future<ModelProviderConfigView> updateModelConfig(
    String id,
    Map<String, Object?> payload,
  ) async {
    final response = await _send('PUT', '/api/models/configs/$id', payload);
    return ModelProviderConfigView.fromJson(
      jsonDecode(response.body) as Map<String, dynamic>,
    );
  }

  Future<void> deleteModelConfig(String id) async {
    await _delete('/api/models/configs/$id');
  }

  Future<List<DeviceTaskEvent>> loadTaskEvents(String taskId) async {
    if (messageBus != null) {
      final data = await messageBus!.request(
        'device.tasks.events',
        params: {'taskId': taskId},
      );
      return (data as List<dynamic>)
          .map((e) => DeviceTaskEvent.fromJson(e as Map<String, dynamic>))
          .toList();
    }
    final response = await _get('/api/device/tasks/$taskId/events');
    final data = jsonDecode(response.body) as List<dynamic>;
    return data
        .map((e) => DeviceTaskEvent.fromJson(e as Map<String, dynamic>))
        .toList();
  }

  Future<List<DeviceTaskSummary>> loadRecentTasks({int limit = 50}) async {
    if (messageBus != null) {
      final data = await messageBus!.request(
        'device.tasks.list',
        params: {'limit': limit},
      );
      return (data as List<dynamic>)
          .map((e) => DeviceTaskSummary.fromJson(e as Map<String, dynamic>))
          .toList();
    }
    final response = await _get('/api/device/tasks?limit=$limit');
    final data = jsonDecode(response.body) as List<dynamic>;
    return data
        .map((e) => DeviceTaskSummary.fromJson(e as Map<String, dynamic>))
        .toList();
  }

  Future<DeviceTaskEvent> approveTask(String taskId) async {
    if (messageBus != null) {
      final envelope = messageBus!.commandFactory.approvalDecision(
        taskId: taskId,
        decision: DeviceApprovalDecision.approve,
        actor: 'mobile',
      );
      await messageBus!.transport.publish(envelope);
      return _localQueuedEvent(envelope);
    }
    final response = await _send('POST', '/api/device/tasks/$taskId/approve', {
      'actor': 'mobile',
    });
    return DeviceTaskEvent.fromJson(
      jsonDecode(response.body) as Map<String, dynamic>,
    );
  }

  Future<DeviceTaskEvent> rejectTask(String taskId) async {
    if (messageBus != null) {
      final envelope = messageBus!.commandFactory.approvalDecision(
        taskId: taskId,
        decision: DeviceApprovalDecision.reject,
        actor: 'mobile',
      );
      await messageBus!.transport.publish(envelope);
      return _localQueuedEvent(envelope);
    }
    final response = await _send('POST', '/api/device/tasks/$taskId/reject', {
      'actor': 'mobile',
    });
    return DeviceTaskEvent.fromJson(
      jsonDecode(response.body) as Map<String, dynamic>,
    );
  }

  Future<http.Response> _get(String path) async {
    final response = await _client.get(Uri.parse('$apiBaseUrl$path'));
    _throwIfFailed(response);
    return response;
  }

  Future<http.Response> _send(
    String method,
    String path,
    Map<String, Object?> payload,
  ) async {
    final uri = Uri.parse('$apiBaseUrl$path');
    final headers = {'Content-Type': 'application/json'};
    final body = jsonEncode(payload);
    final response = method == 'PUT'
        ? await _client.put(uri, headers: headers, body: body)
        : await _client.post(uri, headers: headers, body: body);
    _throwIfFailed(response);
    return response;
  }

  Future<http.Response> _delete(String path) async {
    final response = await _client.delete(Uri.parse('$apiBaseUrl$path'));
    _throwIfFailed(response);
    return response;
  }

  void _throwIfFailed(http.Response response) {
    if (response.statusCode >= 400) {
      throw ApiException(response.statusCode, response.body);
    }
  }

  Future<void> close() async {
    _client.close();
    await messageBus?.close();
  }

  DeviceTaskEvent _localQueuedEvent(dynamic envelope) {
    final payload = envelope.payload as Map<String, Object?>;
    return DeviceTaskEvent(
      eventId: 'local-${payload['commandId'] ?? payload['taskId']}',
      taskId: payload['taskId']?.toString() ?? '',
      userId: payload['userId']?.toString() ?? '',
      deviceId: payload['deviceId']?.toString() ?? '',
      type: DeviceTaskStatus.queued,
      status: DeviceTaskStatus.queued,
      level: 'info',
      message: 'Command published to message bus',
      output: '',
      recoverable: false,
      payload: {'commandType': payload['type'], 'text': payload['text'] ?? ''},
      createdAt: DateTime.now().toUtc().toIso8601String(),
    );
  }
}

class ApiException implements Exception {
  final int statusCode;
  final String body;

  ApiException(this.statusCode, this.body);

  @override
  String toString() => 'Request failed: $statusCode $body';
}
