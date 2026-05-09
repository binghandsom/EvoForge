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

class DeviceTaskEventPage {
  final List<DeviceTaskEvent> items;
  final bool hasMoreBefore;
  final bool hasMoreAfter;
  final String beforeCursor;
  final String afterCursor;
  final int limit;

  DeviceTaskEventPage({
    required this.items,
    required this.hasMoreBefore,
    required this.hasMoreAfter,
    required this.beforeCursor,
    required this.afterCursor,
    required this.limit,
  });

  factory DeviceTaskEventPage.fromJson(Map<String, dynamic> json) {
    return DeviceTaskEventPage(
      items: (json['items'] as List<dynamic>? ?? [])
          .map((item) => DeviceTaskEvent.fromJson(item as Map<String, dynamic>))
          .toList(),
      hasMoreBefore: json['hasMoreBefore'] == true,
      hasMoreAfter: json['hasMoreAfter'] == true,
      beforeCursor: json['beforeCursor']?.toString() ?? '',
      afterCursor: json['afterCursor']?.toString() ?? '',
      limit: int.tryParse(json['limit']?.toString() ?? '') ?? 80,
    );
  }

  factory DeviceTaskEventPage.fromItems(List<DeviceTaskEvent> items) {
    return DeviceTaskEventPage(
      items: items,
      hasMoreBefore: false,
      hasMoreAfter: false,
      beforeCursor: '',
      afterCursor: '',
      limit: items.length,
    );
  }
}
