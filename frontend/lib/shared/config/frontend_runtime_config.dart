import 'dart:convert';

import 'package:flutter/foundation.dart';
import 'package:flutter/services.dart';

import '../messaging/device_mobile_connection_config.dart';
import '../models/device_status.dart';

const frontendConfigAsset = String.fromEnvironment(
  'EVOFORGE_FRONTEND_CONFIG_ASSET',
  defaultValue: 'config/evoforge.local.json',
);

const frontendConfigFallbackAsset = 'config/evoforge.example.json';

class FrontendRuntimeConfig {
  final String sourceAsset;
  final bool fallbackUsed;
  final MobileConnectionRuntimeConfig mobileConnection;

  const FrontendRuntimeConfig({
    required this.sourceAsset,
    required this.fallbackUsed,
    required this.mobileConnection,
  });

  factory FrontendRuntimeConfig.empty({
    String sourceAsset = frontendConfigFallbackAsset,
    bool fallbackUsed = true,
  }) {
    return FrontendRuntimeConfig(
      sourceAsset: sourceAsset,
      fallbackUsed: fallbackUsed,
      mobileConnection: MobileConnectionRuntimeConfig.empty(),
    );
  }

  factory FrontendRuntimeConfig.fromJson(
    Map<String, dynamic> json, {
    required String sourceAsset,
    bool fallbackUsed = false,
  }) {
    return FrontendRuntimeConfig(
      sourceAsset: sourceAsset,
      fallbackUsed: fallbackUsed,
      mobileConnection: MobileConnectionRuntimeConfig.fromJson(
        json['mobileConnection'] as Map<String, dynamic>? ?? {},
      ),
    );
  }

  static Future<FrontendRuntimeConfig> load({
    AssetBundle? bundle,
    String assetPath = frontendConfigAsset,
    String fallbackAssetPath = frontendConfigFallbackAsset,
  }) async {
    final selected = await _loadOptionalAsset(bundle, assetPath);
    if (selected != null) {
      return FrontendRuntimeConfig.fromJson(
        _decodeObject(selected),
        sourceAsset: assetPath,
      );
    }

    final fallback = await _loadOptionalAsset(bundle, fallbackAssetPath);
    if (fallback != null) {
      return FrontendRuntimeConfig.fromJson(
        _decodeObject(fallback),
        sourceAsset: fallbackAssetPath,
        fallbackUsed: true,
      );
    }

    return FrontendRuntimeConfig.empty(
      sourceAsset: fallbackAssetPath,
      fallbackUsed: true,
    );
  }

  static Future<String?> _loadOptionalAsset(
    AssetBundle? bundle,
    String assetPath,
  ) async {
    try {
      return await (bundle ?? rootBundle).loadString(assetPath);
    } on FlutterError {
      return null;
    }
  }

  static Map<String, dynamic> _decodeObject(String source) {
    final decoded = jsonDecode(source);
    if (decoded is Map<String, dynamic>) {
      return decoded;
    }
    throw const FormatException('Frontend config root must be a JSON object.');
  }
}

class MobileConnectionRuntimeConfig {
  final DeviceMobileTransportKind transportKind;
  final Uri? uri;
  final String rabbitMqLogin;
  final String rabbitMqPasscode;
  final String rabbitMqVirtualHost;
  final String commandSigningSecret;
  final String eventSigningSecret;
  final bool subscribeToDedicatedQueue;
  final String userId;
  final String deviceId;
  final String commandExchange;
  final String? commandRoutingKey;
  final String? requestRoutingKey;
  final String eventExchange;
  final String? eventRoutingKey;
  final String? eventQueue;

  const MobileConnectionRuntimeConfig({
    required this.transportKind,
    required this.uri,
    this.rabbitMqLogin = '',
    this.rabbitMqPasscode = '',
    this.rabbitMqVirtualHost = '/',
    this.commandSigningSecret = '',
    this.eventSigningSecret = '',
    this.subscribeToDedicatedQueue = false,
    this.userId = 'local-user',
    this.deviceId = 'local-pc',
    this.commandExchange = 'evoforge.commands',
    this.commandRoutingKey,
    this.requestRoutingKey,
    this.eventExchange = 'evoforge.events',
    this.eventRoutingKey,
    this.eventQueue,
  });

  factory MobileConnectionRuntimeConfig.empty() {
    return const MobileConnectionRuntimeConfig(
      transportKind: DeviceMobileTransportKind.rabbitMqWebStomp,
      uri: null,
    );
  }

  factory MobileConnectionRuntimeConfig.fromJson(Map<String, dynamic> json) {
    final transportKind = _parseTransportKind(json['transport']?.toString());
    final uriText = _firstNonEmpty([
      json['url']?.toString(),
      json['uri']?.toString(),
      json['webStompUrl']?.toString(),
      json['relayUrl']?.toString(),
    ]);

    return MobileConnectionRuntimeConfig(
      transportKind: transportKind,
      uri: uriText == null ? null : Uri.tryParse(uriText),
      rabbitMqLogin:
          json['login']?.toString() ?? json['rabbitMqLogin']?.toString() ?? '',
      rabbitMqPasscode: json['passcode']?.toString() ??
          json['rabbitMqPasscode']?.toString() ??
          '',
      rabbitMqVirtualHost:
          json['virtualHost']?.toString() ?? json['vhost']?.toString() ?? '/',
      commandSigningSecret: json['commandSigningSecret']?.toString() ?? '',
      eventSigningSecret: json['eventSigningSecret']?.toString() ?? '',
      subscribeToDedicatedQueue: json['subscribeToDedicatedQueue'] == true,
      userId: json['userId']?.toString() ?? 'local-user',
      deviceId: json['deviceId']?.toString() ?? 'local-pc',
      commandExchange:
          json['commandExchange']?.toString() ?? 'evoforge.commands',
      commandRoutingKey: json['commandRoutingKey']?.toString(),
      requestRoutingKey: json['requestRoutingKey']?.toString(),
      eventExchange: json['eventExchange']?.toString() ?? 'evoforge.events',
      eventRoutingKey: json['eventRoutingKey']?.toString(),
      eventQueue: json['eventQueue']?.toString(),
    );
  }

  DeviceMobileConnectionConfig toDirectDeviceConnection() {
    final resolvedUri = uri ?? Uri();
    final commandRoute =
        commandRoutingKey ?? 'user.$userId.device.$deviceId.command';
    final requestRoute =
        requestRoutingKey ?? 'user.$userId.device.$deviceId.request';
    final eventRoute = eventRoutingKey ?? 'user.$userId.device.$deviceId.event';
    final queue = eventQueue ?? 'evoforge.device.$deviceId.events';
    if (transportKind == DeviceMobileTransportKind.jsonRelay) {
      return DeviceMobileConnectionConfig.jsonRelay(
        uri: resolvedUri,
        userId: userId,
        deviceId: deviceId,
        commandExchange: commandExchange,
        commandRoutingKey: commandRoute,
        requestRoutingKey: requestRoute,
        eventExchange: eventExchange,
        eventRoutingKey: eventRoute,
        eventQueue: queue,
        commandSigningSecret: commandSigningSecret,
        eventSigningSecret: eventSigningSecret,
      );
    }
    return DeviceMobileConnectionConfig.rabbitMqWebStomp(
      uri: resolvedUri,
      userId: userId,
      deviceId: deviceId,
      commandExchange: commandExchange,
      commandRoutingKey: commandRoute,
      requestRoutingKey: requestRoute,
      eventExchange: eventExchange,
      eventRoutingKey: eventRoute,
      eventQueue: queue,
      login: rabbitMqLogin,
      passcode: rabbitMqPasscode,
      virtualHost: rabbitMqVirtualHost,
      commandSigningSecret: commandSigningSecret,
      eventSigningSecret: eventSigningSecret,
      subscribeToDedicatedQueue: subscribeToDedicatedQueue,
    );
  }

  DeviceMobileConnectionConfig toDeviceConnection(DeviceStatus status) {
    final resolvedUri = uri ?? Uri();
    if (transportKind == DeviceMobileTransportKind.jsonRelay) {
      return DeviceMobileConnectionConfig.fromStatus(
        status,
        uri: resolvedUri,
        transportKind: DeviceMobileTransportKind.jsonRelay,
        commandSigningSecret: commandSigningSecret,
        eventSigningSecret: eventSigningSecret,
      );
    }

    return DeviceMobileConnectionConfig.fromStatus(
      status,
      uri: resolvedUri,
      rabbitMqLogin: rabbitMqLogin,
      rabbitMqPasscode: rabbitMqPasscode,
      rabbitMqVirtualHost: rabbitMqVirtualHost,
      commandSigningSecret: commandSigningSecret,
      eventSigningSecret: eventSigningSecret,
      subscribeToDedicatedQueue: subscribeToDedicatedQueue,
    );
  }

  static DeviceMobileTransportKind _parseTransportKind(String? value) {
    return switch (value?.trim().toLowerCase()) {
      'json_relay' ||
      'jsonrelay' ||
      'relay' =>
        DeviceMobileTransportKind.jsonRelay,
      _ => DeviceMobileTransportKind.rabbitMqWebStomp,
    };
  }

  static String? _firstNonEmpty(Iterable<String?> values) {
    for (final value in values) {
      final trimmed = value?.trim() ?? '';
      if (trimmed.isNotEmpty) return trimmed;
    }
    return null;
  }
}
