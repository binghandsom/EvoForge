import 'dart:collection';
import 'dart:convert';

import 'package:crypto/crypto.dart';

import '../models/device_task_event.dart';

class DeviceEventVerifier {
  static const signatureAttribute = 'eventSignature';

  final String secret;

  DeviceEventVerifier({required this.secret});

  bool get enabled => secret.isNotEmpty;

  bool verify(DeviceTaskEvent event) {
    if (!enabled) return true;
    final actual = event.payload[signatureAttribute]?.toString();
    if (actual == null || actual.isEmpty) return false;
    return actual == signatureFor(event);
  }

  String signatureFor(DeviceTaskEvent event) {
    final mac = Hmac(sha256, utf8.encode(secret));
    return mac
        .convert(utf8.encode(canonicalPayload(event)))
        .bytes
        .map((byte) => byte.toRadixString(16).padLeft(2, '0'))
        .join();
  }

  String canonicalPayload(DeviceTaskEvent event) {
    return jsonEncode(_sorted({
      'eventId': event.eventId,
      'taskId': event.taskId,
      'userId': event.userId,
      'deviceId': event.deviceId,
      'type': event.type,
      'status': event.status,
      'level': event.level,
      'message': event.message,
      'output': event.output.isEmpty ? null : event.output,
      'recoverable': event.recoverable,
      'createdAt': event.createdAt,
      'payload': _signedPayload(event.payload),
    }));
  }

  static Map<String, Object?> _signedPayload(Map<String, dynamic> payload) {
    return Map<String, Object?>.fromEntries(
      payload.entries
          .where((entry) => entry.key != signatureAttribute)
          .map((entry) => MapEntry(entry.key, entry.value)),
    );
  }

  static Object? _sorted(Object? value) {
    if (value is Map) {
      final keys = value.keys.map((key) => key.toString()).toList()..sort();
      return LinkedHashMap<String, Object?>.fromEntries(
        keys.map((key) => MapEntry(key, _sorted(value[key]))),
      );
    }
    if (value is Iterable) {
      return value.map(_sorted).toList();
    }
    return value;
  }
}
