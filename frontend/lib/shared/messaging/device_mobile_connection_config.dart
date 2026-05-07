import '../models/device_status.dart';

enum DeviceMobileTransportKind {
  rabbitMqWebStomp,
  jsonRelay,
}

class DeviceMobileConnectionConfig {
  final DeviceMobileTransportKind transportKind;
  final Uri uri;
  final String userId;
  final String deviceId;
  final String commandExchange;
  final String commandRoutingKey;
  final String requestRoutingKey;
  final String eventExchange;
  final String eventRoutingKey;
  final String eventQueue;
  final String rabbitMqLogin;
  final String rabbitMqPasscode;
  final String rabbitMqVirtualHost;
  final String commandSigningSecret;
  final String eventSigningSecret;
  final bool subscribeToDedicatedQueue;

  const DeviceMobileConnectionConfig({
    required this.transportKind,
    required this.uri,
    required this.userId,
    required this.deviceId,
    required this.commandExchange,
    required this.commandRoutingKey,
    required this.requestRoutingKey,
    required this.eventExchange,
    required this.eventRoutingKey,
    required this.eventQueue,
    this.rabbitMqLogin = '',
    this.rabbitMqPasscode = '',
    this.rabbitMqVirtualHost = '/',
    this.commandSigningSecret = '',
    this.eventSigningSecret = '',
    this.subscribeToDedicatedQueue = false,
  });

  factory DeviceMobileConnectionConfig.rabbitMqWebStomp({
    required Uri uri,
    required String userId,
    required String deviceId,
    required String commandExchange,
    required String commandRoutingKey,
    required String requestRoutingKey,
    required String eventExchange,
    required String eventRoutingKey,
    required String eventQueue,
    required String login,
    required String passcode,
    String virtualHost = '/',
    String commandSigningSecret = '',
    String eventSigningSecret = '',
    bool subscribeToDedicatedQueue = false,
  }) {
    return DeviceMobileConnectionConfig(
      transportKind: DeviceMobileTransportKind.rabbitMqWebStomp,
      uri: uri,
      userId: userId,
      deviceId: deviceId,
      commandExchange: commandExchange,
      commandRoutingKey: commandRoutingKey,
      requestRoutingKey: requestRoutingKey,
      eventExchange: eventExchange,
      eventRoutingKey: eventRoutingKey,
      eventQueue: eventQueue,
      rabbitMqLogin: login,
      rabbitMqPasscode: passcode,
      rabbitMqVirtualHost: virtualHost,
      commandSigningSecret: commandSigningSecret,
      eventSigningSecret: eventSigningSecret,
      subscribeToDedicatedQueue: subscribeToDedicatedQueue,
    );
  }

  factory DeviceMobileConnectionConfig.jsonRelay({
    required Uri uri,
    required String userId,
    required String deviceId,
    required String commandExchange,
    required String commandRoutingKey,
    required String requestRoutingKey,
    required String eventExchange,
    required String eventRoutingKey,
    required String eventQueue,
    String commandSigningSecret = '',
    String eventSigningSecret = '',
  }) {
    return DeviceMobileConnectionConfig(
      transportKind: DeviceMobileTransportKind.jsonRelay,
      uri: uri,
      userId: userId,
      deviceId: deviceId,
      commandExchange: commandExchange,
      commandRoutingKey: commandRoutingKey,
      requestRoutingKey: requestRoutingKey,
      eventExchange: eventExchange,
      eventRoutingKey: eventRoutingKey,
      eventQueue: eventQueue,
      commandSigningSecret: commandSigningSecret,
      eventSigningSecret: eventSigningSecret,
    );
  }

  factory DeviceMobileConnectionConfig.fromStatus(
    DeviceStatus status, {
    required Uri uri,
    DeviceMobileTransportKind transportKind =
        DeviceMobileTransportKind.rabbitMqWebStomp,
    String rabbitMqLogin = '',
    String rabbitMqPasscode = '',
    String rabbitMqVirtualHost = '/',
    String commandSigningSecret = '',
    String eventSigningSecret = '',
    bool subscribeToDedicatedQueue = false,
  }) {
    return DeviceMobileConnectionConfig(
      transportKind: transportKind,
      uri: uri,
      userId: status.userId,
      deviceId: status.deviceId,
      commandExchange: status.commandExchange,
      commandRoutingKey: status.commandRoutingKey,
      requestRoutingKey: status.requestRoutingKey.isNotEmpty
          ? status.requestRoutingKey
          : _defaultRequestRoutingKey(status.userId, status.deviceId),
      eventExchange: status.eventExchange,
      eventRoutingKey: status.eventRoutingKey,
      eventQueue: status.eventQueue,
      rabbitMqLogin: rabbitMqLogin,
      rabbitMqPasscode: rabbitMqPasscode,
      rabbitMqVirtualHost: rabbitMqVirtualHost,
      commandSigningSecret: commandSigningSecret,
      eventSigningSecret: eventSigningSecret,
      subscribeToDedicatedQueue: subscribeToDedicatedQueue,
    );
  }

  String get transportLabel {
    return switch (transportKind) {
      DeviceMobileTransportKind.rabbitMqWebStomp => 'RabbitMQ Web STOMP',
      DeviceMobileTransportKind.jsonRelay => 'JSON WebSocket Relay',
    };
  }

  bool get commandSigningConfigured => commandSigningSecret.isNotEmpty;

  bool get eventSigningConfigured => eventSigningSecret.isNotEmpty;

  bool get rabbitMqCredentialsConfigured {
    return rabbitMqLogin.isNotEmpty && rabbitMqPasscode.isNotEmpty;
  }

  bool get readyForConnection {
    final commonReady = uri.hasScheme &&
        uri.host.isNotEmpty &&
        userId.isNotEmpty &&
        deviceId.isNotEmpty &&
        commandExchange.isNotEmpty &&
        commandRoutingKey.isNotEmpty &&
        requestRoutingKey.isNotEmpty;
    if (!commonReady) return false;
    return switch (transportKind) {
      DeviceMobileTransportKind.rabbitMqWebStomp =>
        rabbitMqCredentialsConfigured &&
            (subscribeToDedicatedQueue
                ? eventQueue.isNotEmpty
                : eventExchange.isNotEmpty && eventRoutingKey.isNotEmpty),
      DeviceMobileTransportKind.jsonRelay => true,
    };
  }

  Map<String, String> describe({bool redactSecrets = true}) {
    return {
      'Transport': transportLabel,
      'URI': uri.toString(),
      'User': userId,
      'Device': deviceId,
      'Command Exchange': commandExchange,
      'Command Routing Key': commandRoutingKey,
      'Request Routing Key': requestRoutingKey,
      'Event Exchange': eventExchange,
      'Event Routing Key': eventRoutingKey,
      'Event Queue': eventQueue,
      if (transportKind == DeviceMobileTransportKind.rabbitMqWebStomp)
        'RabbitMQ Login': rabbitMqLogin,
      if (transportKind == DeviceMobileTransportKind.rabbitMqWebStomp)
        'RabbitMQ Passcode': _secretLabel(rabbitMqPasscode, redactSecrets),
      if (transportKind == DeviceMobileTransportKind.rabbitMqWebStomp)
        'Virtual Host': rabbitMqVirtualHost,
      if (transportKind == DeviceMobileTransportKind.rabbitMqWebStomp)
        'Event Subscribe Mode':
            subscribeToDedicatedQueue ? 'Dedicated Queue' : 'Exchange Route',
      'Command Signing Secret':
          _secretLabel(commandSigningSecret, redactSecrets),
      'Event Signing Secret': _secretLabel(eventSigningSecret, redactSecrets),
      'Ready': readyForConnection ? 'yes' : 'no',
    };
  }

  static String _secretLabel(String secret, bool redact) {
    if (secret.isEmpty) return 'not configured';
    if (!redact) return secret;
    return 'configured';
  }

  static String _defaultRequestRoutingKey(String userId, String deviceId) {
    return 'user.$userId.device.$deviceId.request';
  }
}
