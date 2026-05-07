import 'package:evoforge_web/shared/config/frontend_runtime_config.dart';
import 'package:evoforge_web/shared/messaging/device_mobile_connection_config.dart';
import 'package:evoforge_web/shared/models/codex_task_status.dart';
import 'package:evoforge_web/shared/models/device_status.dart';
import 'package:flutter_test/flutter_test.dart';

void main() {
  test('builds RabbitMQ Web STOMP mobile config from frontend JSON', () {
    final config = FrontendRuntimeConfig.fromJson({
      'mobileConnection': {
        'transport': 'rabbitmq_web_stomp',
        'url': 'wss://mq.example.com/ws',
        'login': 'mobile-user',
        'passcode': 'mobile-secret',
        'virtualHost': 'evoforge',
        'commandSigningSecret': 'command-secret',
        'eventSigningSecret': 'event-secret',
      },
    }, sourceAsset: 'config/evoforge.local.json');

    final connection = config.mobileConnection.toDeviceConnection(_status());

    expect(
        connection.transportKind, DeviceMobileTransportKind.rabbitMqWebStomp);
    expect(connection.uri.toString(), 'wss://mq.example.com/ws');
    expect(connection.rabbitMqLogin, 'mobile-user');
    expect(connection.rabbitMqPasscode, 'mobile-secret');
    expect(connection.rabbitMqVirtualHost, 'evoforge');
    expect(connection.commandSigningSecret, 'command-secret');
    expect(connection.eventSigningSecret, 'event-secret');
    expect(connection.readyForConnection, isTrue);
  });

  test('builds JSON relay config without RabbitMQ credentials', () {
    final config = FrontendRuntimeConfig.fromJson({
      'mobileConnection': {
        'transport': 'json_relay',
        'url': 'wss://relay.example.com/evoforge/device',
      },
    }, sourceAsset: 'config/evoforge.local.json');

    final connection = config.mobileConnection.toDeviceConnection(_status());

    expect(connection.transportKind, DeviceMobileTransportKind.jsonRelay);
    expect(
        connection.uri.toString(), 'wss://relay.example.com/evoforge/device');
    expect(connection.rabbitMqLogin, '');
    expect(connection.readyForConnection, isTrue);
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
