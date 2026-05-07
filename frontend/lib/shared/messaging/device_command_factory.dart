import '../models/device_protocol.dart';
import '../security/device_command_signer.dart';

class DeviceCommandFactory {
  final String userId;
  final String deviceId;
  final String commandExchange;
  final String? commandRoutingKey;
  final String? clientRequestRoutingKey;
  final DeviceCommandSigner signer;
  final DateTime Function() clock;
  final String Function() idFactory;

  DeviceCommandFactory({
    required this.userId,
    required this.deviceId,
    required this.signer,
    this.commandExchange = 'evoforge.commands',
    this.commandRoutingKey,
    this.clientRequestRoutingKey,
    DateTime Function()? clock,
    String Function()? idFactory,
  })  : clock = clock ?? (() => DateTime.now().toUtc()),
        idFactory = idFactory ?? DeviceCommandFactory.defaultId;

  DeviceCommandEnvelope naturalLanguageTask({
    required String text,
    bool requiresApproval = false,
    String? taskId,
    String? skillId,
    String? llm,
    String? codeModel,
    Map<String, Object?> attributes = const {},
  }) {
    return _command(
      type: DeviceCommandType.naturalLanguageTask,
      text: text,
      requiresApproval: requiresApproval,
      taskId: taskId,
      skillId: skillId,
      llm: llm,
      codeModel: codeModel,
      attributes: attributes,
    );
  }

  DeviceCommandEnvelope codexTask({
    required String text,
    bool requiresApproval = true,
    String? taskId,
    Map<String, Object?> attributes = const {},
  }) {
    return _command(
      type: DeviceCommandType.codexTask,
      text: text,
      requiresApproval: requiresApproval,
      taskId: taskId,
      attributes: attributes,
    );
  }

  DeviceCommandEnvelope approvalDecision({
    required String taskId,
    required String decision,
    String actor = 'mobile',
    String? note,
    Map<String, Object?> attributes = const {},
  }) {
    return _command(
      type: DeviceCommandType.approvalDecision,
      taskId: taskId,
      text: decision,
      requiresApproval: false,
      attributes: {
        ...attributes,
        'decision': decision,
        'actor': actor,
        if (note != null && note.isNotEmpty) 'note': note,
      },
    );
  }

  DeviceCommandEnvelope clientRequest({
    required String method,
    Map<String, Object?> params = const {},
    String? taskId,
    String? requestId,
  }) {
    final id = requestId ?? taskId ?? idFactory();
    return _command(
      type: DeviceCommandType.clientRequest,
      taskId: taskId ?? id,
      requiresApproval: false,
      routingKey: clientRequestRoutingKey,
      attributes: {
        'request': {
          'requestId': id,
          'method': method,
          'params': params,
        },
      },
    );
  }

  DeviceCommandEnvelope _command({
    required String type,
    String? text,
    bool requiresApproval = false,
    String? taskId,
    String? skillId,
    String? llm,
    String? codeModel,
    String? routingKey,
    Map<String, Object?> attributes = const {},
  }) {
    final command = <String, Object?>{
      'commandId': idFactory(),
      'taskId': taskId ?? idFactory(),
      'userId': userId,
      'deviceId': deviceId,
      'type': type,
      'text': text,
      'skillId': skillId,
      'llm': llm,
      'codeModel': codeModel,
      'requiresApproval': requiresApproval,
      'attributes': Map<String, Object?>.from(attributes),
      'createdAt': clock().toUtc().toIso8601String(),
    }..removeWhere((_, value) => value == null);

    return DeviceCommandEnvelope(
      exchange: commandExchange,
      routingKey: routingKey ??
          commandRoutingKey ??
          'user.$userId.device.$deviceId.command',
      payload: signer.sign(command),
    );
  }

  static String defaultId() {
    return DeviceCommandSigner.uuidV4();
  }
}

class DeviceCommandEnvelope {
  final String exchange;
  final String routingKey;
  final Map<String, Object?> payload;

  const DeviceCommandEnvelope({
    required this.exchange,
    required this.routingKey,
    required this.payload,
  });

  String get commandId => payload['commandId']?.toString() ?? '';

  String get taskId => payload['taskId']?.toString() ?? '';

  String get type => payload['type']?.toString() ?? '';

  Map<String, Object?> toPublishRequest() {
    return {
      'exchange': exchange,
      'routingKey': routingKey,
      'payload': payload,
    };
  }
}
