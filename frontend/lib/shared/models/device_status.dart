import 'codex_task_status.dart';

class DeviceStatus {
  final bool enabled;
  final String userId;
  final String deviceId;
  final String commandExchange;
  final String eventExchange;
  final String commandQueue;
  final String requestQueue;
  final String eventQueue;
  final String commandRoutingKey;
  final String requestRoutingKey;
  final String eventRoutingKey;
  final int heartbeatSeconds;
  final List<String> commandTypes;
  final bool commandSigningEnabled;
  final int commandSigningTtlSeconds;
  final String commandReplayStore;
  final bool persistentReplayProtection;
  final bool eventSigningEnabled;
  final CodexTaskStatus codexTask;
  final TesterStatus tester;
  final List<String> capabilities;

  DeviceStatus({
    required this.enabled,
    required this.userId,
    required this.deviceId,
    required this.commandExchange,
    required this.eventExchange,
    required this.commandQueue,
    required this.requestQueue,
    required this.eventQueue,
    required this.commandRoutingKey,
    required this.requestRoutingKey,
    required this.eventRoutingKey,
    required this.heartbeatSeconds,
    required this.commandTypes,
    required this.commandSigningEnabled,
    required this.commandSigningTtlSeconds,
    required this.commandReplayStore,
    required this.persistentReplayProtection,
    required this.eventSigningEnabled,
    required this.codexTask,
    required this.tester,
    required this.capabilities,
  });

  factory DeviceStatus.fromJson(Map<String, dynamic> json) {
    return DeviceStatus(
      enabled: json['enabled'] == true,
      userId: json['userId']?.toString() ?? '',
      deviceId: json['deviceId']?.toString() ?? '',
      commandExchange: json['commandExchange']?.toString() ?? '',
      eventExchange: json['eventExchange']?.toString() ?? '',
      commandQueue: json['commandQueue']?.toString() ?? '',
      requestQueue: json['requestQueue']?.toString() ?? '',
      eventQueue: json['eventQueue']?.toString() ?? '',
      commandRoutingKey: json['commandRoutingKey']?.toString() ?? '',
      requestRoutingKey: json['requestRoutingKey']?.toString() ?? '',
      eventRoutingKey: json['eventRoutingKey']?.toString() ?? '',
      heartbeatSeconds:
          int.tryParse(json['heartbeatSeconds']?.toString() ?? '') ?? 0,
      commandTypes: (json['commandTypes'] as List<dynamic>? ?? [])
          .map((e) => e.toString())
          .toList(),
      commandSigningEnabled: commandSigning(json)['enabled'] == true,
      commandSigningTtlSeconds: int.tryParse(
            commandSigning(json)['ttlSeconds']?.toString() ?? '',
          ) ??
          0,
      commandReplayStore:
          commandSigning(json)['replayStore']?.toString() ?? 'memory',
      persistentReplayProtection:
          commandSigning(json)['persistentReplayProtection'] == true,
      eventSigningEnabled: eventSigning(json)['enabled'] == true,
      codexTask: CodexTaskStatus.fromJson(
        json['codexTask'] as Map<String, dynamic>?,
      ),
      tester: TesterStatus.fromJson(
        json['tester'] as Map<String, dynamic>?,
      ),
      capabilities: (json['capabilities'] as List<dynamic>? ?? [])
          .map((e) => e.toString())
          .toList(),
    );
  }

  static Map<String, dynamic> commandSigning(Map<String, dynamic> json) {
    return json['commandSigning'] as Map<String, dynamic>? ?? {};
  }

  static Map<String, dynamic> eventSigning(Map<String, dynamic> json) {
    return json['eventSigning'] as Map<String, dynamic>? ?? {};
  }
}

class TesterStatus {
  final bool enabled;
  final bool requiresApproval;
  final int timeoutSeconds;
  final String commandSource;
  final bool autoDiscoverEnabled;
  final bool modelDiscoveryEnabled;
  final bool autoOptimizeEnabled;
  final List<TesterProjectCommands> projectCommands;

  TesterStatus({
    required this.enabled,
    required this.requiresApproval,
    required this.timeoutSeconds,
    required this.commandSource,
    required this.autoDiscoverEnabled,
    required this.modelDiscoveryEnabled,
    required this.autoOptimizeEnabled,
    required this.projectCommands,
  });

  factory TesterStatus.fromJson(Map<String, dynamic>? json) {
    return TesterStatus(
      enabled: json?['enabled'] != false,
      requiresApproval: json?['requiresApproval'] == true,
      timeoutSeconds:
          int.tryParse(json?['timeoutSeconds']?.toString() ?? '') ?? 0,
      commandSource: json?['commandSource']?.toString() ?? '',
      autoDiscoverEnabled: json?['autoDiscoverEnabled'] != false,
      modelDiscoveryEnabled: json?['modelDiscoveryEnabled'] == true,
      autoOptimizeEnabled: json?['autoOptimizeEnabled'] != false,
      projectCommands: (json?['projectCommands'] as List<dynamic>? ?? [])
          .map((item) => TesterProjectCommands.fromJson(item))
          .toList(),
    );
  }
}

class TesterProjectCommands {
  final String projectKey;
  final List<TesterCommandStatus> commands;

  TesterProjectCommands({
    required this.projectKey,
    required this.commands,
  });

  factory TesterProjectCommands.fromJson(Object? json) {
    final map = json as Map<String, dynamic>? ?? {};
    return TesterProjectCommands(
      projectKey: map['projectKey']?.toString() ?? '',
      commands: (map['commands'] as List<dynamic>? ?? [])
          .map((item) => TesterCommandStatus.fromJson(item))
          .toList(),
    );
  }
}

class TesterCommandStatus {
  final String recordId;
  final String projectKey;
  final String id;
  final String name;
  final String type;
  final bool enabled;
  final List<String> covers;
  final List<String> tags;
  final String cost;
  final String confidence;
  final String evidenceParser;
  final String command;
  final String workingDirectory;
  final int timeoutSeconds;
  final String reason;
  final String source;
  final String optimizationNotes;
  final int successCount;
  final int failureCount;
  final String lastStatus;
  final int? lastExitCode;
  final int? lastDurationMs;
  final String lastOutputExcerpt;
  final String lastRunAt;

  TesterCommandStatus({
    required this.recordId,
    required this.projectKey,
    required this.id,
    required this.name,
    required this.type,
    required this.enabled,
    required this.covers,
    required this.tags,
    required this.cost,
    required this.confidence,
    required this.evidenceParser,
    required this.command,
    required this.workingDirectory,
    required this.timeoutSeconds,
    required this.reason,
    required this.source,
    required this.optimizationNotes,
    required this.successCount,
    required this.failureCount,
    required this.lastStatus,
    required this.lastExitCode,
    required this.lastDurationMs,
    required this.lastOutputExcerpt,
    required this.lastRunAt,
  });

  factory TesterCommandStatus.fromJson(Object? json) {
    final map = json as Map<String, dynamic>? ?? {};
    return TesterCommandStatus(
      recordId: map['recordId']?.toString() ?? '',
      projectKey: map['projectKey']?.toString() ?? '',
      id: map['id']?.toString() ?? '',
      name: map['name']?.toString() ?? '',
      type: map['type']?.toString() ?? '',
      enabled: map['enabled'] != false,
      covers: (map['covers'] as List<dynamic>? ?? [])
          .map((e) => e.toString())
          .toList(),
      tags: (map['tags'] as List<dynamic>? ?? [])
          .map((e) => e.toString())
          .toList(),
      cost: map['cost']?.toString() ?? '',
      confidence: map['confidence']?.toString() ?? '',
      evidenceParser: map['evidenceParser']?.toString() ?? '',
      command: map['command']?.toString() ?? '',
      workingDirectory: map['workingDirectory']?.toString() ?? '',
      timeoutSeconds:
          int.tryParse(map['timeoutSeconds']?.toString() ?? '') ?? 0,
      reason: map['reason']?.toString() ?? '',
      source: map['source']?.toString() ?? '',
      optimizationNotes: map['optimizationNotes']?.toString() ?? '',
      successCount: int.tryParse(map['successCount']?.toString() ?? '') ?? 0,
      failureCount: int.tryParse(map['failureCount']?.toString() ?? '') ?? 0,
      lastStatus: map['lastStatus']?.toString() ?? '',
      lastExitCode: int.tryParse(map['lastExitCode']?.toString() ?? ''),
      lastDurationMs: int.tryParse(map['lastDurationMs']?.toString() ?? ''),
      lastOutputExcerpt: map['lastOutputExcerpt']?.toString() ?? '',
      lastRunAt: map['lastRunAt']?.toString() ?? '',
    );
  }
}
