import 'dart:async';
import 'dart:convert';

import '../models/device_task_event.dart';
import 'device_command_factory.dart';
import 'device_json_relay_transport.dart';
import 'device_message_transport.dart';
import 'device_socket_client.dart';

class DeviceRabbitMqWebStompTransport implements DeviceMessageTransport {
  final DeviceSocketClient socket;
  final String login;
  final String passcode;
  final String virtualHost;
  final String subscriptionId;
  final String eventDestination;
  final Map<String, String> connectHeaders;
  final Map<String, String> subscribeHeaders;
  final StreamController<DeviceTaskEvent> _events =
      StreamController<DeviceTaskEvent>.broadcast();
  final StreamController<Object> _protocolErrors =
      StreamController<Object>.broadcast();
  final DeviceStompFrameParser _parser = DeviceStompFrameParser();
  final Completer<void> _connected = Completer<void>();
  late final StreamSubscription<Object?> _subscription;
  bool _streamsClosed = false;

  DeviceRabbitMqWebStompTransport({
    required this.socket,
    required this.login,
    required this.passcode,
    required this.eventDestination,
    this.virtualHost = '/',
    this.subscriptionId = 'evoforge-mobile-events',
    this.connectHeaders = const {},
    this.subscribeHeaders = const {},
  }) {
    _subscription = socket.messages.listen(
      _handleSocketMessage,
      onError: _handleSocketError,
      onDone: _handleSocketDone,
    );
    unawaited(_sendConnect());
  }

  factory DeviceRabbitMqWebStompTransport.connect({
    required Uri uri,
    required String login,
    required String passcode,
    required String eventExchange,
    required String eventRoutingKey,
    String virtualHost = '/',
    Iterable<String>? protocols,
    String subscriptionId = 'evoforge-mobile-events',
    Map<String, String> connectHeaders = const {},
    Map<String, String> subscribeHeaders = const {},
  }) {
    return DeviceRabbitMqWebStompTransport(
      socket: DeviceWebSocketClient.connect(uri, protocols: protocols),
      login: login,
      passcode: passcode,
      virtualHost: virtualHost,
      subscriptionId: subscriptionId,
      eventDestination: DeviceStompDestination.exchange(
        eventExchange,
        eventRoutingKey,
      ),
      connectHeaders: connectHeaders,
      subscribeHeaders: subscribeHeaders,
    );
  }

  factory DeviceRabbitMqWebStompTransport.connectToQueue({
    required Uri uri,
    required String login,
    required String passcode,
    required String eventQueue,
    String virtualHost = '/',
    Iterable<String>? protocols,
    String subscriptionId = 'evoforge-mobile-events',
    Map<String, String> connectHeaders = const {},
    Map<String, String> subscribeHeaders = const {},
  }) {
    return DeviceRabbitMqWebStompTransport(
      socket: DeviceWebSocketClient.connect(uri, protocols: protocols),
      login: login,
      passcode: passcode,
      virtualHost: virtualHost,
      subscriptionId: subscriptionId,
      eventDestination: DeviceStompDestination.amqQueue(eventQueue),
      connectHeaders: connectHeaders,
      subscribeHeaders: subscribeHeaders,
    );
  }

  @override
  Stream<DeviceTaskEvent> get events => _events.stream;

  Future<void> get connected => _connected.future;

  Stream<Object> get protocolErrors => _protocolErrors.stream;

  @override
  Future<void> publish(DeviceCommandEnvelope envelope) async {
    await connected;
    await _sendFrame(DeviceStompFrame(
      command: 'SEND',
      headers: {
        'destination': DeviceStompDestination.exchange(
          envelope.exchange,
          envelope.routingKey,
        ),
        'content-type': 'application/json',
        'persistent': 'true',
      },
      body: jsonEncode(envelope.payload),
    ));
  }

  Future<void> close() async {
    if (_connected.isCompleted && !_streamsClosed) {
      await _sendFrame(const DeviceStompFrame(command: 'DISCONNECT'));
    }
    await _subscription.cancel();
    await _closeStreams();
    await socket.close();
  }

  Future<void> _sendConnect() {
    return _sendFrame(DeviceStompFrame(
      command: 'CONNECT',
      headers: {
        'accept-version': '1.2',
        'host': virtualHost,
        'login': login,
        'passcode': passcode,
        'heart-beat': '0,0',
        ...connectHeaders,
      },
    ));
  }

  Future<void> _sendSubscribe() {
    return _sendFrame(DeviceStompFrame(
      command: 'SUBSCRIBE',
      headers: {
        'id': subscriptionId,
        'destination': eventDestination,
        'ack': 'auto',
        ...subscribeHeaders,
      },
    ));
  }

  Future<void> _sendFrame(DeviceStompFrame frame) {
    return socket.send(DeviceStompCodec.encode(frame));
  }

  void _handleSocketMessage(Object? message) {
    try {
      for (final frame in _parser.add(message)) {
        _handleFrame(frame);
      }
    } on Object catch (error) {
      _protocolErrors.add(error);
    }
  }

  void _handleFrame(DeviceStompFrame frame) {
    switch (frame.command) {
      case 'CONNECTED':
        if (!_connected.isCompleted) {
          _connected.complete();
        }
        unawaited(_sendSubscribe());
      case 'MESSAGE':
        _handleEventBody(frame.body);
      case 'ERROR':
        final error = DeviceStompProtocolException(
          frame.headers['message'] ?? 'RabbitMQ STOMP error',
          frame.body,
        );
        if (!_connected.isCompleted) {
          _connected.completeError(error);
        }
        _protocolErrors.add(error);
      default:
        break;
    }
  }

  void _handleEventBody(String body) {
    try {
      final decoded = DeviceJsonRelayCodec.decode(body);
      final events = DeviceJsonRelayCodec.eventsFrom(decoded);
      for (final event in events) {
        _events.add(event);
      }
    } on Object catch (error) {
      _protocolErrors.add(error);
    }
  }

  void _handleSocketError(Object error) {
    if (!_connected.isCompleted) {
      _connected.completeError(error);
    }
    _protocolErrors.add(error);
  }

  void _handleSocketDone() {
    if (!_connected.isCompleted) {
      _connected.completeError(
        const DeviceStompProtocolException('RabbitMQ STOMP socket closed'),
      );
    }
    unawaited(_closeStreams());
  }

  Future<void> _closeStreams() async {
    if (_streamsClosed) return;
    _streamsClosed = true;
    await Future.wait([
      _events.close(),
      _protocolErrors.close(),
    ]);
  }
}

class DeviceStompDestination {
  static String exchange(String exchange, String routingKey) {
    return '/exchange/${_escapePath(exchange)}/${_escapePath(routingKey)}';
  }

  static String amqQueue(String queue) {
    return '/amq/queue/${_escapePath(queue)}';
  }

  static String _escapePath(String value) {
    return Uri.encodeComponent(value).replaceAll('%2E', '.');
  }
}

class DeviceStompFrame {
  final String command;
  final Map<String, String> headers;
  final String body;

  const DeviceStompFrame({
    required this.command,
    this.headers = const {},
    this.body = '',
  });
}

class DeviceStompCodec {
  static String encode(DeviceStompFrame frame) {
    final buffer = StringBuffer()..writeln(frame.command);
    for (final entry in frame.headers.entries) {
      buffer
        ..write(_escapeHeader(entry.key))
        ..write(':')
        ..writeln(_escapeHeader(entry.value));
    }
    buffer
      ..writeln()
      ..write(frame.body)
      ..write('\u0000');
    return buffer.toString();
  }

  static DeviceStompFrame decode(String frame) {
    final normalized = frame.replaceAll('\r\n', '\n');
    final separator = normalized.indexOf('\n\n');
    if (separator < 0) {
      throw const FormatException('Invalid STOMP frame');
    }

    final headerLines = normalized.substring(0, separator).split('\n');
    if (headerLines.isEmpty || headerLines.first.isEmpty) {
      throw const FormatException('STOMP command is missing');
    }

    final headers = <String, String>{};
    for (final line in headerLines.skip(1)) {
      if (line.isEmpty) continue;
      final colon = line.indexOf(':');
      if (colon <= 0) {
        throw FormatException('Invalid STOMP header: $line');
      }
      headers[_unescapeHeader(line.substring(0, colon))] =
          _unescapeHeader(line.substring(colon + 1));
    }

    return DeviceStompFrame(
      command: headerLines.first,
      headers: headers,
      body: normalized.substring(separator + 2),
    );
  }

  static String _escapeHeader(String value) {
    return value
        .replaceAll(r'\', r'\\')
        .replaceAll('\r', r'\r')
        .replaceAll('\n', r'\n')
        .replaceAll(':', r'\c');
  }

  static String _unescapeHeader(String value) {
    final buffer = StringBuffer();
    for (var index = 0; index < value.length; index++) {
      final character = value[index];
      if (character != r'\') {
        buffer.write(character);
        continue;
      }
      if (index == value.length - 1) {
        buffer.write(character);
        continue;
      }

      final escaped = value[++index];
      switch (escaped) {
        case 'c':
          buffer.write(':');
        case 'n':
          buffer.write('\n');
        case 'r':
          buffer.write('\r');
        case r'\':
          buffer.write(r'\');
        default:
          buffer
            ..write(character)
            ..write(escaped);
      }
    }
    return buffer.toString();
  }
}

class DeviceStompFrameParser {
  String _buffer = '';

  List<DeviceStompFrame> add(Object? chunk) {
    if (chunk == null) return const [];
    _buffer += chunk is List<int> ? utf8.decode(chunk) : chunk.toString();
    final frames = <DeviceStompFrame>[];

    while (_buffer.isNotEmpty) {
      _dropHeartbeats();
      final terminator = _buffer.indexOf('\u0000');
      if (terminator < 0) break;

      final rawFrame = _buffer.substring(0, terminator);
      _buffer = _buffer.substring(terminator + 1);
      if (rawFrame.trim().isEmpty) continue;
      frames.add(DeviceStompCodec.decode(rawFrame));
    }

    return frames;
  }

  void _dropHeartbeats() {
    while (_buffer.startsWith('\n') || _buffer.startsWith('\r')) {
      _buffer = _buffer.substring(1);
    }
  }
}

class DeviceStompProtocolException implements Exception {
  final String message;
  final String body;

  const DeviceStompProtocolException(this.message, [this.body = '']);

  @override
  String toString() {
    return body.isEmpty ? message : '$message: $body';
  }
}
