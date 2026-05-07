import 'package:web_socket_channel/web_socket_channel.dart';

abstract interface class DeviceSocketClient {
  Stream<Object?> get messages;

  Future<void> send(Object? message);

  Future<void> close();
}

class DeviceWebSocketClient implements DeviceSocketClient {
  final WebSocketChannel _channel;
  final Future<void> ready;

  DeviceWebSocketClient(this._channel) : ready = _channel.ready;

  factory DeviceWebSocketClient.connect(
    Uri uri, {
    Iterable<String>? protocols,
  }) {
    return DeviceWebSocketClient(
      WebSocketChannel.connect(uri, protocols: protocols),
    );
  }

  @override
  Stream<Object?> get messages => _channel.stream;

  @override
  Future<void> send(Object? message) async {
    await ready;
    _channel.sink.add(message);
  }

  @override
  Future<void> close() {
    return _channel.sink.close();
  }
}
