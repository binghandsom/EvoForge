import 'dart:async';
import 'dart:convert';

import '../models/device_task_event.dart';
import 'device_command_factory.dart';
import 'device_message_transport.dart';
import 'device_socket_client.dart';

class DeviceJsonRelayTransport implements DeviceMessageTransport {
  final DeviceSocketClient client;
  final String commandMessageType;
  final String eventMessageType;
  final StreamController<DeviceTaskEvent> _events =
      StreamController<DeviceTaskEvent>.broadcast();
  final StreamController<Object> _malformedMessages =
      StreamController<Object>.broadcast();
  late final StreamSubscription<Object?> _subscription;
  bool _streamsClosed = false;

  DeviceJsonRelayTransport({
    required this.client,
    this.commandMessageType = DeviceJsonRelayCodec.commandMessageType,
    this.eventMessageType = DeviceJsonRelayCodec.eventMessageType,
  }) {
    _subscription = client.messages.listen(
      _handleMessage,
      onError: _malformedMessages.add,
      onDone: () => unawaited(_closeStreams()),
    );
  }

  factory DeviceJsonRelayTransport.connect(
    Uri uri, {
    Iterable<String>? protocols,
    String commandMessageType = DeviceJsonRelayCodec.commandMessageType,
    String eventMessageType = DeviceJsonRelayCodec.eventMessageType,
  }) {
    return DeviceJsonRelayTransport(
      client: DeviceWebSocketClient.connect(uri, protocols: protocols),
      commandMessageType: commandMessageType,
      eventMessageType: eventMessageType,
    );
  }

  @override
  Stream<DeviceTaskEvent> get events => _events.stream;

  Stream<Object> get malformedMessages => _malformedMessages.stream;

  @override
  Future<void> publish(DeviceCommandEnvelope envelope) {
    return client.send(
      DeviceJsonRelayCodec.encodePublishMessage(
        envelope,
        type: commandMessageType,
      ),
    );
  }

  Future<void> close() async {
    await _subscription.cancel();
    await _closeStreams();
    await client.close();
  }

  void _handleMessage(Object? message) {
    try {
      final decoded = DeviceJsonRelayCodec.decode(message);
      final events = DeviceJsonRelayCodec.eventsFrom(
        decoded,
        eventMessageType: eventMessageType,
      );
      for (final event in events) {
        _events.add(event);
      }
    } on Object catch (error) {
      _malformedMessages.add(error);
    }
  }

  Future<void> _closeStreams() async {
    if (_streamsClosed) return;
    _streamsClosed = true;
    await Future.wait([
      _events.close(),
      _malformedMessages.close(),
    ]);
  }
}

class DeviceJsonRelayCodec {
  static const commandMessageType = 'device_command';
  static const eventMessageType = 'device_event';

  static Map<String, Object?> publishMessage(
    DeviceCommandEnvelope envelope, {
    String type = commandMessageType,
  }) {
    return {
      'type': type,
      ...envelope.toPublishRequest(),
    };
  }

  static String encodePublishMessage(
    DeviceCommandEnvelope envelope, {
    String type = commandMessageType,
  }) {
    return jsonEncode(publishMessage(envelope, type: type));
  }

  static Object? decode(Object? message) {
    if (message is String) {
      return jsonDecode(message);
    }
    if (message is List<int>) {
      return jsonDecode(utf8.decode(message));
    }
    return message;
  }

  static List<DeviceTaskEvent> eventsFrom(
    Object? message, {
    String eventMessageType = eventMessageType,
  }) {
    final normalized = _normalizeJson(message);
    if (normalized is List) {
      return normalized
          .expand(
            (entry) => eventsFrom(
              entry,
              eventMessageType: eventMessageType,
            ),
          )
          .toList();
    }
    if (normalized is! Map<String, dynamic>) {
      throw const FormatException('Relay message must be a JSON object');
    }

    final events = normalized['events'];
    if (events is List) {
      return events
          .expand(
            (entry) => eventsFrom(
              entry,
              eventMessageType: eventMessageType,
            ),
          )
          .toList();
    }

    final wrappedEvent = normalized['event'];
    if (wrappedEvent != null) {
      return eventsFrom(wrappedEvent, eventMessageType: eventMessageType);
    }

    final payload = normalized['payload'];
    if (normalized['type'] == eventMessageType && payload != null) {
      return eventsFrom(payload, eventMessageType: eventMessageType);
    }
    if (payload is Map<String, dynamic> && _looksLikeEvent(payload)) {
      return [DeviceTaskEvent.fromJson(payload)];
    }
    if (_looksLikeEvent(normalized)) {
      return [DeviceTaskEvent.fromJson(normalized)];
    }

    throw const FormatException(
        'Relay message does not contain a device event');
  }

  static bool _looksLikeEvent(Map<String, dynamic> json) {
    return json.containsKey('eventId') &&
        json.containsKey('taskId') &&
        json.containsKey('type') &&
        json.containsKey('status');
  }

  static Object? _normalizeJson(Object? value) {
    if (value is Map) {
      return <String, dynamic>{
        for (final entry in value.entries)
          entry.key.toString(): _normalizeJson(entry.value),
      };
    }
    if (value is List) {
      return value.map(_normalizeJson).toList();
    }
    return value;
  }
}
