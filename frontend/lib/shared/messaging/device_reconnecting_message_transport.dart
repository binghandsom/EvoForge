import 'dart:async';

import '../models/device_task_event.dart';
import 'device_command_factory.dart';
import 'device_message_transport.dart';

typedef DeviceTransportConnector = DeviceMessageTransport Function();

class DeviceReconnectingMessageTransport implements DeviceMessageTransport {
  final DeviceTransportConnector connector;
  final Duration initialDelay;
  final Duration maxDelay;
  final StreamController<DeviceTaskEvent> _events =
      StreamController<DeviceTaskEvent>.broadcast();
  final StreamController<Object> _errors = StreamController<Object>.broadcast();
  StreamSubscription<DeviceTaskEvent>? _subscription;
  DeviceMessageTransport? _transport;
  Future<void>? _connectFuture;
  Timer? _reconnectTimer;
  bool _closed = false;
  int _attempt = 0;

  DeviceReconnectingMessageTransport({
    required this.connector,
    this.initialDelay = const Duration(milliseconds: 500),
    this.maxDelay = const Duration(seconds: 12),
  }) {
    unawaited(_connectQuietly());
  }

  @override
  Stream<DeviceTaskEvent> get events => _events.stream;

  Stream<Object> get errors => _errors.stream;

  Future<void> _connectQuietly() async {
    try {
      await _ensureConnected();
    } on Object catch (error) {
      _errors.add(error);
    }
  }

  @override
  Future<void> publish(DeviceCommandEnvelope envelope) async {
    Object? lastError;
    for (var attempt = 0; attempt < 2; attempt++) {
      final transport = await _ensureConnected();
      try {
        await transport.publish(envelope);
        return;
      } on Object catch (error) {
        lastError = error;
        _errors.add(error);
        await _replaceTransport();
      }
    }
    throw lastError ?? StateError('Message transport is not connected');
  }

  Future<void> close() async {
    _closed = true;
    _reconnectTimer?.cancel();
    _reconnectTimer = null;
    await _subscription?.cancel();
    _subscription = null;
    final transport = _transport;
    _transport = null;
    if (transport != null) {
      await _closeTransport(transport);
    }
    await Future.wait([
      _events.close(),
      _errors.close(),
    ]);
  }

  Future<DeviceMessageTransport> _ensureConnected() async {
    if (_closed) {
      throw StateError('Message transport is closed');
    }
    final current = _transport;
    if (current != null) {
      return current;
    }
    final existing = _connectFuture;
    if (existing != null) {
      await existing;
      final connected = _transport;
      if (connected != null) return connected;
    }
    final completer = Completer<void>();
    _connectFuture = completer.future;
    try {
      final next = connector();
      _transport = next;
      _subscription = next.events.listen(
        _events.add,
        onError: (Object error) {
          _errors.add(error);
          unawaited(_replaceTransport());
        },
        onDone: () {
          if (!_closed) {
            unawaited(_replaceTransport());
          }
        },
      );
      _attempt = 0;
      completer.complete();
      return next;
    } on Object catch (error) {
      _errors.add(error);
      completer.complete();
      _scheduleReconnect();
      rethrow;
    } finally {
      if (identical(_connectFuture, completer.future)) {
        _connectFuture = null;
      }
    }
  }

  Future<void> _replaceTransport() async {
    if (_closed) return;
    await _subscription?.cancel();
    _subscription = null;
    final transport = _transport;
    _transport = null;
    if (transport != null) {
      await _closeTransport(transport);
    }
    _scheduleReconnect();
  }

  void _scheduleReconnect() {
    if (_closed || _reconnectTimer != null) return;
    final delay = _nextDelay();
    _reconnectTimer = Timer(delay, () {
      _reconnectTimer = null;
      if (!_closed) {
        unawaited(_ensureConnected());
      }
    });
  }

  Duration _nextDelay() {
    final multiplier = 1 << (_attempt > 5 ? 5 : _attempt);
    _attempt += 1;
    final milliseconds = initialDelay.inMilliseconds * multiplier;
    return Duration(
      milliseconds: milliseconds > maxDelay.inMilliseconds
          ? maxDelay.inMilliseconds
          : milliseconds,
    );
  }

  Future<void> _closeTransport(DeviceMessageTransport transport) async {
    try {
      final closeResult = (transport as dynamic).close();
      if (closeResult is Future) {
        await closeResult;
      }
    } on NoSuchMethodError {
      return;
    }
  }
}
