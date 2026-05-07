class AgentConversationThread {
  final String threadId;
  final String title;
  final String summary;
  final Map<String, dynamic> metadata;
  final int turnCount;
  final String lastRole;
  final String lastContent;
  final String createdAt;
  final String updatedAt;

  AgentConversationThread({
    required this.threadId,
    required this.title,
    required this.summary,
    required this.metadata,
    required this.turnCount,
    required this.lastRole,
    required this.lastContent,
    required this.createdAt,
    required this.updatedAt,
  });

  factory AgentConversationThread.fromJson(Map<String, dynamic> json) {
    return AgentConversationThread(
      threadId: json['threadId']?.toString() ?? '',
      title: json['title']?.toString() ?? '新对话',
      summary: json['summary']?.toString() ?? '',
      metadata: json['metadata'] as Map<String, dynamic>? ?? {},
      turnCount: json['turnCount'] is int
          ? json['turnCount'] as int
          : int.tryParse(json['turnCount']?.toString() ?? '') ?? 0,
      lastRole: json['lastRole']?.toString() ?? '',
      lastContent: json['lastContent']?.toString() ?? '',
      createdAt: json['createdAt']?.toString() ?? '',
      updatedAt: json['updatedAt']?.toString() ?? '',
    );
  }
}

class AgentConversationTurn {
  final String id;
  final String threadId;
  final String role;
  final String content;
  final Map<String, dynamic> metadata;
  final String createdAt;

  AgentConversationTurn({
    required this.id,
    required this.threadId,
    required this.role,
    required this.content,
    required this.metadata,
    required this.createdAt,
  });

  factory AgentConversationTurn.fromJson(Map<String, dynamic> json) {
    return AgentConversationTurn(
      id: json['id']?.toString() ?? '',
      threadId: json['threadId']?.toString() ?? '',
      role: json['role']?.toString() ?? '',
      content: json['content']?.toString() ?? '',
      metadata: json['metadata'] as Map<String, dynamic>? ?? {},
      createdAt: json['createdAt']?.toString() ?? '',
    );
  }
}
