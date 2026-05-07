import 'dart:collection';
import 'dart:convert';
import 'dart:math';

import 'package:crypto/crypto.dart';

class DeviceCommandSigner {
  static const signatureAttribute = 'signature';

  final String secret;
  final DateTime Function() clock;
  final String Function() idFactory;

  DeviceCommandSigner({
    required this.secret,
    DateTime Function()? clock,
    String Function()? idFactory,
  })  : clock = clock ?? (() => DateTime.now().toUtc()),
        idFactory = idFactory ?? _defaultIdFactory;

  bool get enabled => secret.isNotEmpty;

  Map<String, Object?> sign(Map<String, Object?> command) {
    if (!enabled) return Map<String, Object?>.from(command);

    final signed = Map<String, Object?>.from(command);
    signed['commandId'] = signed['commandId']?.toString().isNotEmpty == true
        ? signed['commandId']
        : idFactory();
    signed['createdAt'] = signed['createdAt']?.toString().isNotEmpty == true
        ? signed['createdAt']
        : clock().toUtc().toIso8601String();

    final attributes = Map<String, Object?>.from(
      signed['attributes'] as Map? ?? const {},
    );
    signed['attributes'] = attributes;
    attributes[signatureAttribute] = signatureFor(signed);
    return signed;
  }

  String signatureFor(Map<String, Object?> command) {
    final mac = Hmac(sha256, utf8.encode(secret));
    return mac
        .convert(utf8.encode(canonicalPayload(command)))
        .bytes
        .map((byte) => byte.toRadixString(16).padLeft(2, '0'))
        .join();
  }

  String canonicalPayload(Map<String, Object?> command) {
    return jsonEncode(_sorted({
      'codeModel': command['codeModel'],
      'commandId': command['commandId'],
      'createdAt': command['createdAt'],
      'deviceId': command['deviceId'],
      'llm': command['llm'],
      'requiresApproval': command['requiresApproval'] == true,
      'skillId': command['skillId'],
      'taskId': command['taskId'],
      'text': command['text'],
      'type': command['type'],
      'userId': command['userId'],
      'attributes': _signedAttributes(command['attributes']),
    }));
  }

  static Map<String, Object?> _signedAttributes(Object? attributes) {
    if (attributes is! Map) return {};
    return Map<String, Object?>.fromEntries(
      attributes.entries
          .where((entry) => entry.key.toString() != signatureAttribute)
          .map((entry) => MapEntry(entry.key.toString(), entry.value)),
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

  static String uuidV4() {
    final random = Random.secure();
    final bytes = List<int>.generate(16, (_) => random.nextInt(256));
    bytes[6] = (bytes[6] & 0x0f) | 0x40;
    bytes[8] = (bytes[8] & 0x3f) | 0x80;
    final hex =
        bytes.map((byte) => byte.toRadixString(16).padLeft(2, '0')).join();
    return '${hex.substring(0, 8)}-${hex.substring(8, 12)}-'
        '${hex.substring(12, 16)}-${hex.substring(16, 20)}-'
        '${hex.substring(20)}';
  }

  static String _defaultIdFactory() => uuidV4();
}
