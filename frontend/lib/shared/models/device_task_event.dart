class DeviceTaskEvent {
  final String eventId;
  final String taskId;
  final String userId;
  final String deviceId;
  final String type;
  final String status;
  final String level;
  final String message;
  final String output;
  final bool recoverable;
  final Map<String, dynamic> payload;
  final String createdAt;

  DeviceTaskEvent({
    required this.eventId,
    required this.taskId,
    required this.userId,
    required this.deviceId,
    required this.type,
    required this.status,
    required this.level,
    required this.message,
    required this.output,
    required this.recoverable,
    required this.payload,
    required this.createdAt,
  });

  factory DeviceTaskEvent.fromJson(Map<String, dynamic> json) {
    return DeviceTaskEvent(
      eventId: json['eventId']?.toString() ?? '',
      taskId: json['taskId']?.toString() ?? '',
      userId: json['userId']?.toString() ?? '',
      deviceId: json['deviceId']?.toString() ?? '',
      type: json['type']?.toString() ?? '',
      status: json['status']?.toString() ?? '',
      level: json['level']?.toString() ?? 'info',
      message: json['message']?.toString() ?? '',
      output: json['output']?.toString() ?? '',
      recoverable: json['recoverable'] == true,
      payload: json['payload'] as Map<String, dynamic>? ?? {},
      createdAt: json['createdAt']?.toString() ?? '',
    );
  }
}
