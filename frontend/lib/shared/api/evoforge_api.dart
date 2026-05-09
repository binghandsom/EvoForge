import 'dart:convert';

import 'package:http/http.dart' as http;

import '../models/device_status.dart';
import '../models/device_task_event.dart';
import '../models/device_task_summary.dart';
import '../models/device_protocol.dart';
import '../models/agent_conversation.dart';
import '../models/model_config.dart';
import '../models/self_learning.dart';
import '../models/skill.dart';
import '../messaging/evoforge_message_bus_client.dart';

const restDiagnosticBaseUrl = String.fromEnvironment(
  'EVOFORGE_REST_DIAGNOSTIC_BASE_URL',
  defaultValue: '',
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
    if (messageBus != null) {
      final data = await messageBus!.request('skills.list');
      return (data as List<dynamic>)
          .map((e) => SkillView.fromJson(e as Map<String, dynamic>))
          .toList();
    }
    final response = await _get('/api/skills');
    final data = jsonDecode(response.body) as List<dynamic>;
    return data
        .map((e) => SkillView.fromJson(e as Map<String, dynamic>))
        .toList();
  }

  Future<SkillDetail> loadSkillDetail(String id) async {
    if (messageBus != null) {
      final data = await messageBus!.request(
        'skills.get',
        params: {'id': id},
      );
      return SkillDetail.fromJson(data as Map<String, dynamic>);
    }
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
    final payload = {
      'name': name,
      'code': code,
      'enabled': false,
      'metadata': metadata,
    };
    if (messageBus != null) {
      await messageBus!.request('skills.create', params: payload);
      return;
    }
    await _send('POST', '/api/skills', payload);
  }

  Future<void> updateSkill(
    String id, {
    required String name,
    required String code,
    Map<String, Object?> metadata = const {},
  }) async {
    final payload = {
      'id': id,
      'name': name,
      'code': code,
      'metadata': metadata,
    };
    if (messageBus != null) {
      await messageBus!.request('skills.update', params: payload);
      return;
    }
    await _send('PUT', '/api/skills/$id', payload);
  }

  Future<void> activateSkill(String id) async {
    if (messageBus != null) {
      await messageBus!.request('skills.activate', params: {'id': id});
      return;
    }
    await _send('POST', '/api/skills/$id/activate', {});
  }

  Future<List<dynamic>> loadSkillHistory(String id) async {
    if (messageBus != null) {
      final data = await messageBus!.request(
        'skills.history',
        params: {'id': id},
      );
      return data as List<dynamic>;
    }
    final response = await _get('/api/skills/$id/history');
    return jsonDecode(response.body) as List<dynamic>;
  }

  Future<List<dynamic>> loadSkillAudit(String id) async {
    if (messageBus != null) {
      final data = await messageBus!.request(
        'skills.audit',
        params: {'id': id},
      );
      return data as List<dynamic>;
    }
    final response = await _get('/api/audit/$id');
    return jsonDecode(response.body) as List<dynamic>;
  }

  Future<Map<String, dynamic>> executeSkill(
    String id, {
    required String input,
    required bool evaluate,
  }) async {
    final payload = {
      'id': id,
      'input': input,
      'evaluate': evaluate,
    };
    if (messageBus != null) {
      final data = await messageBus!.request('skills.execute', params: payload);
      return data as Map<String, dynamic>;
    }
    final response = await _send('POST', '/api/skills/$id/execute', payload);
    return jsonDecode(response.body) as Map<String, dynamic>;
  }

  Future<Map<String, dynamic>> proposeSkill({
    required String name,
    required String prompt,
  }) async {
    final payload = {
      'name': name,
      'prompt': prompt,
    };
    if (messageBus != null) {
      final data = await messageBus!.request('skills.propose', params: payload);
      return data as Map<String, dynamic>;
    }
    final response = await _send('POST', '/api/skills/propose', payload);
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
      final envelope = switch (type) {
        DeviceCommandType.codexTask => messageBus!.commandFactory.codexTask(
            text: text,
            requiresApproval: requiresApproval,
            attributes: attributes,
          ),
        DeviceCommandType.testerTask => messageBus!.commandFactory.testerTask(
            text: text,
            requiresApproval: requiresApproval,
            attributes: attributes,
          ),
        DeviceCommandType.humanResponse =>
          messageBus!.commandFactory.humanResponse(
            questionId: attributes['questionId']?.toString() ??
                ((attributes['codexQuestionAnswer'] as Map?)?['questionId']
                        ?.toString() ??
                    ''),
            answer: text,
            questionTaskId: attributes['questionTaskId']?.toString() ??
                ((attributes['codexQuestionAnswer'] as Map?)?['taskId']
                    ?.toString()),
            actor: attributes['actor']?.toString() ?? 'mobile',
            note: attributes['note']?.toString(),
            attributes: attributes,
          ),
        _ => messageBus!.commandFactory.naturalLanguageTask(
            text: text,
            requiresApproval: requiresApproval,
            attributes: attributes,
          ),
      };
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

  Future<DeviceTaskEvent> answerCodexQuestion({
    required String questionId,
    required String answer,
    String? questionTaskId,
    String actor = 'mobile',
    String? note,
  }) {
    return dispatchDeviceCommand(
      text: answer,
      type: DeviceCommandType.humanResponse,
      requiresApproval: false,
      attributes: {
        'questionId': questionId,
        if (questionTaskId != null && questionTaskId.isNotEmpty)
          'questionTaskId': questionTaskId,
        'actor': actor,
        if (note != null && note.isNotEmpty) 'note': note,
        'codexQuestionAnswer': {
          'questionId': questionId,
          if (questionTaskId != null && questionTaskId.isNotEmpty)
            'taskId': questionTaskId,
          'answer': answer,
          'actor': actor,
          if (note != null && note.isNotEmpty) 'note': note,
        },
      },
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
    if (messageBus != null) {
      final data = await messageBus!.request('models.configs.list');
      return (data as List<dynamic>)
          .map((e) =>
              ModelProviderConfigView.fromJson(e as Map<String, dynamic>))
          .toList();
    }
    final response = await _get('/api/models/configs');
    final data = jsonDecode(response.body) as List<dynamic>;
    return data
        .map((e) => ModelProviderConfigView.fromJson(e as Map<String, dynamic>))
        .toList();
  }

  Future<SelfLearningDashboard> loadSelfLearningDashboard({
    int limit = 80,
  }) async {
    if (messageBus != null) {
      final data = await messageBus!.request(
        'selfLearning.dashboard',
        params: {'limit': limit},
      );
      return SelfLearningDashboard.fromJson(data as Map<String, dynamic>);
    }
    final response = await _get('/api/self-learning/dashboard?limit=$limit');
    return SelfLearningDashboard.fromJson(
      jsonDecode(response.body) as Map<String, dynamic>,
    );
  }

  Future<ModelProviderConfigView> createModelConfig(
    Map<String, Object?> payload,
  ) async {
    if (messageBus != null) {
      final data = await messageBus!.request(
        'models.configs.save',
        params: payload,
      );
      return ModelProviderConfigView.fromJson(data as Map<String, dynamic>);
    }
    final response = await _send('POST', '/api/models/configs', payload);
    return ModelProviderConfigView.fromJson(
      jsonDecode(response.body) as Map<String, dynamic>,
    );
  }

  Future<ModelProviderConfigView> updateModelConfig(
    String id,
    Map<String, Object?> payload,
  ) async {
    if (messageBus != null) {
      final data = await messageBus!.request(
        'models.configs.save',
        params: {'id': id, ...payload},
      );
      return ModelProviderConfigView.fromJson(data as Map<String, dynamic>);
    }
    final response = await _send('PUT', '/api/models/configs/$id', payload);
    return ModelProviderConfigView.fromJson(
      jsonDecode(response.body) as Map<String, dynamic>,
    );
  }

  Future<void> deleteModelConfig(String id) async {
    if (messageBus != null) {
      await messageBus!.request('models.configs.delete', params: {'id': id});
      return;
    }
    await _delete('/api/models/configs/$id');
  }

  Future<List<TesterCommandStatus>> loadTesterCapabilities(
    String projectKey,
  ) async {
    if (messageBus != null) {
      final data = await messageBus!.request(
        'tester.capabilities.list',
        params: {'projectKey': projectKey},
      );
      return (data as List<dynamic>)
          .map((e) => TesterCommandStatus.fromJson(e))
          .toList();
    }
    final response = await _get(
        '/api/tester/capabilities?projectKey=${_encode(projectKey)}');
    final data = jsonDecode(response.body) as List<dynamic>;
    return data.map((e) => TesterCommandStatus.fromJson(e)).toList();
  }

  Future<List<TesterCommandStatus>> discoverTesterCapabilities({
    required String projectKey,
    bool useModel = false,
  }) async {
    final payload = {'projectKey': projectKey, 'useModel': useModel};
    if (messageBus != null) {
      final data = await messageBus!.request(
        'tester.capabilities.discover',
        params: payload,
      ) as Map<String, dynamic>;
      return (data['saved'] as List<dynamic>? ?? [])
          .map((e) => TesterCommandStatus.fromJson(e))
          .toList();
    }
    final response =
        await _send('POST', '/api/tester/capabilities/discover', payload);
    final data = jsonDecode(response.body) as Map<String, dynamic>;
    return (data['saved'] as List<dynamic>? ?? [])
        .map((e) => TesterCommandStatus.fromJson(e))
        .toList();
  }

  Future<TesterCommandStatus> saveTesterCapability(
    Map<String, Object?> payload,
  ) async {
    if (messageBus != null) {
      final data = await messageBus!.request(
        'tester.capabilities.save',
        params: payload,
      );
      return TesterCommandStatus.fromJson(data);
    }
    final projectKey = payload['projectKey']?.toString() ?? '';
    final id = payload['id']?.toString() ?? '';
    final response = id.isEmpty
        ? await _send('POST', '/api/tester/capabilities', payload)
        : await _send(
            'PUT',
            '/api/tester/capabilities/${_encode(projectKey)}/${_encode(id)}',
            payload,
          );
    return TesterCommandStatus.fromJson(
      jsonDecode(response.body) as Map<String, dynamic>,
    );
  }

  Future<void> deleteTesterCapability({
    required String projectKey,
    required String id,
  }) async {
    if (messageBus != null) {
      await messageBus!.request(
        'tester.capabilities.delete',
        params: {'projectKey': projectKey, 'id': id},
      );
      return;
    }
    await _delete(
      '/api/tester/capabilities/${_encode(projectKey)}/${_encode(id)}',
    );
  }

  Future<List<DeviceTaskEvent>> loadTaskEvents(String taskId) async {
    final page = await loadTaskEventPage(taskId: taskId, limit: 200);
    return page.items;
  }

  Future<DeviceTaskEventPage> loadTaskEventPage({
    required String taskId,
    int limit = 80,
    String before = '',
    String after = '',
  }) async {
    final params = {
      'taskId': taskId,
      'limit': limit,
      if (before.isNotEmpty) 'before': before,
      if (after.isNotEmpty) 'after': after,
    };
    if (messageBus != null) {
      final data = await messageBus!.request(
        'device.tasks.events.page',
        params: params,
      );
      return DeviceTaskEventPage.fromJson(data as Map<String, dynamic>);
    }
    final query = [
      'limit=$limit',
      if (before.isNotEmpty) 'before=${_encode(before)}',
      if (after.isNotEmpty) 'after=${_encode(after)}',
    ].join('&');
    final response =
        await _get('/api/device/tasks/${_encode(taskId)}/events/page?$query');
    return DeviceTaskEventPage.fromJson(
      jsonDecode(response.body) as Map<String, dynamic>,
    );
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
    final response = await _client.get(_restUri(path));
    _throwIfFailed(response);
    return response;
  }

  Future<http.Response> _send(
    String method,
    String path,
    Map<String, Object?> payload,
  ) async {
    final uri = _restUri(path);
    final headers = {'Content-Type': 'application/json'};
    final body = jsonEncode(payload);
    final response = method == 'PUT'
        ? await _client.put(uri, headers: headers, body: body)
        : await _client.post(uri, headers: headers, body: body);
    _throwIfFailed(response);
    return response;
  }

  Future<http.Response> _delete(String path) async {
    final response = await _client.delete(_restUri(path));
    _throwIfFailed(response);
    return response;
  }

  Uri _restUri(String path) {
    final base = restDiagnosticBaseUrl.trim();
    if (base.isEmpty) {
      throw ApiException(
        0,
        'REST diagnostic base URL is not configured. Use the message bus frontend config for normal console traffic.',
      );
    }
    return Uri.parse('$base$path');
  }

  static String _encode(String value) => Uri.encodeComponent(value);

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
