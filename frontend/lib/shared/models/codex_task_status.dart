class CodexTaskStatus {
  final bool enabled;
  final bool requiresApproval;
  final String defaultWorkspace;
  final String workingDirectory;
  final List<CodexWorkspaceStatus> workspaces;
  final int timeoutSeconds;

  CodexTaskStatus({
    required this.enabled,
    required this.requiresApproval,
    required this.defaultWorkspace,
    required this.workingDirectory,
    required this.workspaces,
    required this.timeoutSeconds,
  });

  factory CodexTaskStatus.fromJson(Map<String, dynamic>? json) {
    return CodexTaskStatus(
      enabled: json?['enabled'] == true,
      requiresApproval: json?['requiresApproval'] != false,
      defaultWorkspace: json?['defaultWorkspace']?.toString() ?? '',
      workingDirectory: json?['workingDirectory']?.toString() ?? '',
      workspaces: (json?['workspaces'] as List<dynamic>? ?? [])
          .map((item) => CodexWorkspaceStatus.fromJson(item))
          .toList(),
      timeoutSeconds:
          int.tryParse(json?['timeoutSeconds']?.toString() ?? '') ?? 0,
    );
  }
}

class CodexWorkspaceStatus {
  final String key;
  final String path;

  CodexWorkspaceStatus({
    required this.key,
    required this.path,
  });

  factory CodexWorkspaceStatus.fromJson(Object? json) {
    final map = json as Map<String, dynamic>? ?? {};
    return CodexWorkspaceStatus(
      key: map['key']?.toString() ?? '',
      path: map['path']?.toString() ?? '',
    );
  }
}
