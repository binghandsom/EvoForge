class DeviceTaskSummary {
  final String taskId;
  final String userId;
  final String deviceId;
  final String type;
  final String status;
  final String level;
  final String message;
  final String commandText;
  final bool recoverable;
  final int eventCount;
  final String firstEventAt;
  final String lastEventAt;

  DeviceTaskSummary({
    required this.taskId,
    required this.userId,
    required this.deviceId,
    required this.type,
    required this.status,
    required this.level,
    required this.message,
    required this.commandText,
    required this.recoverable,
    required this.eventCount,
    required this.firstEventAt,
    required this.lastEventAt,
  });

  factory DeviceTaskSummary.fromJson(Map<String, dynamic> json) {
    return DeviceTaskSummary(
      taskId: json['taskId']?.toString() ?? '',
      userId: json['userId']?.toString() ?? '',
      deviceId: json['deviceId']?.toString() ?? '',
      type: json['type']?.toString() ?? '',
      status: json['status']?.toString() ?? '',
      level: json['level']?.toString() ?? 'info',
      message: json['message']?.toString() ?? '',
      commandText: json['commandText']?.toString() ?? '',
      recoverable: json['recoverable'] == true,
      eventCount: int.tryParse(json['eventCount']?.toString() ?? '') ?? 0,
      firstEventAt: json['firstEventAt']?.toString() ?? '',
      lastEventAt: json['lastEventAt']?.toString() ?? '',
    );
  }
}
