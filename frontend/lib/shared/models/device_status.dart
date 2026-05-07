import 'codex_task_status.dart';

class DeviceStatus {
  final bool enabled;
  final String userId;
  final String deviceId;
  final String commandExchange;
  final String eventExchange;
  final String commandQueue;
  final String eventQueue;
  final String commandRoutingKey;
  final String eventRoutingKey;
  final int heartbeatSeconds;
  final List<String> commandTypes;
  final bool commandSigningEnabled;
  final int commandSigningTtlSeconds;
  final String commandReplayStore;
  final bool persistentReplayProtection;
  final bool eventSigningEnabled;
  final CodexTaskStatus codexTask;
  final List<String> capabilities;

  DeviceStatus({
    required this.enabled,
    required this.userId,
    required this.deviceId,
    required this.commandExchange,
    required this.eventExchange,
    required this.commandQueue,
    required this.eventQueue,
    required this.commandRoutingKey,
    required this.eventRoutingKey,
    required this.heartbeatSeconds,
    required this.commandTypes,
    required this.commandSigningEnabled,
    required this.commandSigningTtlSeconds,
    required this.commandReplayStore,
    required this.persistentReplayProtection,
    required this.eventSigningEnabled,
    required this.codexTask,
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
      eventQueue: json['eventQueue']?.toString() ?? '',
      commandRoutingKey: json['commandRoutingKey']?.toString() ?? '',
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
