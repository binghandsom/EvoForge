import 'package:evoforge_web/shared/messaging/device_mobile_connection_config.dart';
import 'package:evoforge_web/shared/models/codex_task_status.dart';
import 'package:evoforge_web/shared/models/device_status.dart';
import 'package:flutter_test/flutter_test.dart';

void main() {
  test('builds redacted RabbitMQ Web STOMP config from device status', () {
    final config = DeviceMobileConnectionConfig.fromStatus(
      _status(),
      uri: Uri.parse('wss://rabbitmq.example.com/ws'),
      rabbitMqLogin: 'mobile-user',
      rabbitMqPasscode: 'rabbit-secret',
      commandSigningSecret: 'command-secret',
      eventSigningSecret: 'event-secret',
    );

    expect(config.transportLabel, 'RabbitMQ Web STOMP');
    expect(config.readyForConnection, isTrue);
    expect(config.commandRoutingKey, 'user.user-1.device.pc-1.command');
    expect(config.requestRoutingKey, 'user.user-1.device.pc-1.request');
    expect(config.eventRoutingKey, 'user.user-1.device.pc-1.event');

    final description = config.describe();
    expect(description['RabbitMQ Passcode'], 'configured');
    expect(description['Command Signing Secret'], 'configured');
    expect(description['Event Signing Secret'], 'configured');
    expect(description['Ready'], 'yes');
  });

  test('requires credentials and event target for RabbitMQ Web STOMP', () {
    final missingCredentials = DeviceMobileConnectionConfig.fromStatus(
      _status(),
      uri: Uri.parse('wss://rabbitmq.example.com/ws'),
    );
    final missingEventRoute = DeviceMobileConnectionConfig.rabbitMqWebStomp(
      uri: Uri.parse('wss://rabbitmq.example.com/ws'),
      userId: 'user-1',
      deviceId: 'pc-1',
      commandExchange: 'evoforge.commands',
      commandRoutingKey: 'user.user-1.device.pc-1.command',
      requestRoutingKey: 'user.user-1.device.pc-1.request',
      eventExchange: '',
      eventRoutingKey: '',
      eventQueue: '',
      login: 'mobile-user',
      passcode: 'rabbit-secret',
    );

    expect(missingCredentials.readyForConnection, isFalse);
    expect(missingEventRoute.readyForConnection, isFalse);
  });

  test('allows JSON relay without RabbitMQ credentials', () {
    final config = DeviceMobileConnectionConfig.fromStatus(
      _status(),
      uri: Uri.parse('wss://relay.example.com/evoforge/device'),
      transportKind: DeviceMobileTransportKind.jsonRelay,
    );

    expect(config.transportLabel, 'JSON WebSocket Relay');
    expect(config.readyForConnection, isTrue);
    expect(config.describe()['RabbitMQ Login'], isNull);
  });

  test('can choose a dedicated queue subscription', () {
    final config = DeviceMobileConnectionConfig.fromStatus(
      _status(),
      uri: Uri.parse('wss://rabbitmq.example.com/ws'),
      rabbitMqLogin: 'mobile-user',
      rabbitMqPasscode: 'rabbit-secret',
      subscribeToDedicatedQueue: true,
    );

    expect(config.readyForConnection, isTrue);
    expect(config.describe()['Event Subscribe Mode'], 'Dedicated Queue');
  });
}

DeviceStatus _status() {
  return DeviceStatus(
    enabled: true,
    userId: 'user-1',
    deviceId: 'pc-1',
    commandExchange: 'evoforge.commands',
    eventExchange: 'evoforge.events',
    commandQueue: 'evoforge.device.pc-1.commands',
    requestQueue: 'evoforge.device.pc-1.requests',
    eventQueue: 'evoforge.device.pc-1.events',
    commandRoutingKey: 'user.user-1.device.pc-1.command',
    requestRoutingKey: 'user.user-1.device.pc-1.request',
    eventRoutingKey: 'user.user-1.device.pc-1.event',
    heartbeatSeconds: 30,
    commandTypes: const ['natural_language_task', 'codex_task'],
    commandSigningEnabled: true,
    commandSigningTtlSeconds: 300,
    commandReplayStore: 'postgres',
    persistentReplayProtection: true,
    eventSigningEnabled: true,
    codexTask: CodexTaskStatus(
      enabled: false,
      requiresApproval: true,
      defaultWorkspace: 'evoforge',
      workingDirectory: '.',
      workspaces: const [],
      timeoutSeconds: 600,
    ),
    capabilities: const ['approval_requests'],
  );
}
