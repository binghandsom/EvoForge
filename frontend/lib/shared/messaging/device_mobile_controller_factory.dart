import '../security/device_command_signer.dart';
import 'device_command_factory.dart';
import 'device_json_relay_transport.dart';
import 'device_message_transport.dart';
import 'device_mobile_connection_config.dart';
import 'device_mobile_controller.dart';
import 'device_mobile_session.dart';
import 'device_rabbitmq_web_stomp_transport.dart';

typedef DeviceMessageTransportBuilder = DeviceMessageTransport Function(
  DeviceMobileConnectionConfig config,
);

class DeviceMobileControllerFactory {
  final DeviceMessageTransportBuilder? transportBuilder;

  const DeviceMobileControllerFactory({this.transportBuilder});

  DeviceMobileController create(DeviceMobileConnectionConfig config) {
    final session = DeviceMobileSession(
      commandFactory: DeviceCommandFactory(
        userId: config.userId,
        deviceId: config.deviceId,
        commandExchange: config.commandExchange,
        commandRoutingKey: config.commandRoutingKey,
        signer: DeviceCommandSigner(secret: config.commandSigningSecret),
      ),
      eventSigningSecret: config.eventSigningSecret,
    );

    return DeviceMobileController(
      session: session,
      transport: transportBuilder?.call(config) ?? _transportFor(config),
    );
  }

  DeviceMessageTransport _transportFor(DeviceMobileConnectionConfig config) {
    return switch (config.transportKind) {
      DeviceMobileTransportKind.rabbitMqWebStomp =>
        config.subscribeToDedicatedQueue
            ? DeviceRabbitMqWebStompTransport.connectToQueue(
                uri: config.uri,
                login: config.rabbitMqLogin,
                passcode: config.rabbitMqPasscode,
                virtualHost: config.rabbitMqVirtualHost,
                eventQueue: config.eventQueue,
              )
            : DeviceRabbitMqWebStompTransport.connect(
                uri: config.uri,
                login: config.rabbitMqLogin,
                passcode: config.rabbitMqPasscode,
                virtualHost: config.rabbitMqVirtualHost,
                eventExchange: config.eventExchange,
                eventRoutingKey: config.eventRoutingKey,
              ),
      DeviceMobileTransportKind.jsonRelay =>
        DeviceJsonRelayTransport.connect(config.uri),
    };
  }
}
