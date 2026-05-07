import 'dart:async';

import '../models/device_task_event.dart';
import 'device_command_factory.dart';
import 'device_message_transport.dart';

class EvoForgeMessageBusClient {
  final DeviceMessageTransport transport;
  final DeviceCommandFactory commandFactory;
  final Duration timeout;
  final StreamController<DeviceTaskEvent> _events =
      StreamController<DeviceTaskEvent>.broadcast();
  late final StreamSubscription<DeviceTaskEvent> _subscription;

  EvoForgeMessageBusClient({
    required this.transport,
    required this.commandFactory,
    this.timeout = const Duration(seconds: 20),
  }) {
    _subscription = transport.events.listen(_events.add);
  }

  Stream<DeviceTaskEvent> get events => _events.stream;

  Future<Object?> request(
    String method, {
    Map<String, Object?> params = const {},
    Duration? timeout,
  }) async {
    final envelope = commandFactory.clientRequest(
      method: method,
      params: params,
    );
    final requestId = envelope.taskId;
    final response = _events.stream.firstWhere(
      (event) {
        final payload = event.payload['clientResponse'];
        return payload is Map && payload['requestId']?.toString() == requestId;
      },
    ).timeout(timeout ?? this.timeout);
    await transport.publish(envelope);
    final event = await response;
    final payload = event.payload['clientResponse'];
    if (payload is! Map) {
      throw const MessageBusException('Missing clientResponse payload');
    }
    if (payload['ok'] == false) {
      throw MessageBusException(payload['error']?.toString() ?? event.message);
    }
    return payload['data'];
  }

  Future<void> close() async {
    await _subscription.cancel();
    await _events.close();
  }
}

class MessageBusException implements Exception {
  final String message;

  const MessageBusException(this.message);

  @override
  String toString() => message;
}
