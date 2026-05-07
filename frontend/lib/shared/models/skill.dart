class SkillView {
  final String id;
  final String name;
  final String version;
  final String language;
  final bool enabled;
  final String status;

  SkillView({
    required this.id,
    required this.name,
    required this.version,
    required this.language,
    required this.enabled,
    required this.status,
  });

  factory SkillView.fromJson(Map<String, dynamic> json) {
    return SkillView(
      id: json['id']?.toString() ?? '',
      name: json['name']?.toString() ?? '',
      version: json['version']?.toString() ?? '',
      language: json['language']?.toString() ?? 'groovy',
      enabled: json['enabled'] == true,
      status: json['status']?.toString() ?? 'DRAFT',
    );
  }
}

class SkillDetail {
  final String id;
  final String name;
  final String code;
  final String status;
  final Map<String, dynamic> metadata;

  SkillDetail({
    required this.id,
    required this.name,
    required this.code,
    required this.status,
    required this.metadata,
  });

  factory SkillDetail.fromJson(Map<String, dynamic> json) {
    final metadata = json['metadata'];
    return SkillDetail(
      id: json['id']?.toString() ?? '',
      name: json['name']?.toString() ?? '',
      code: json['code']?.toString() ?? '',
      status: json['status']?.toString() ?? 'DRAFT',
      metadata: metadata is Map
          ? Map<String, dynamic>.from(metadata)
          : <String, dynamic>{},
    );
  }
}
