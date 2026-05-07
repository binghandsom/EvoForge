import '../models/device_task_event.dart';
import 'device_command_factory.dart';

abstract interface class DeviceMessageTransport {
  Stream<DeviceTaskEvent> get events;

  Future<void> publish(DeviceCommandEnvelope envelope);
}
