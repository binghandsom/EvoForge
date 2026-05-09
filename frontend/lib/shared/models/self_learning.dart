class SelfLearningDashboard {
  final SelfLearningSummary summary;
  final List<SelfLearningCapabilityArea> capabilityAreas;
  final List<SelfLearningSideQuest> activeSideQuests;
  final List<SelfLearningMilestone> milestones;
  final List<SelfLearningActivity> activities;
  final SelfLearningVelocity learningVelocity;
  final SelfLearningResourceUtilization resourceUtilization;
  final SelfLearningModelEvolution modelEvolution;
  final List<SelfLearningInterfaceExample> interfaceExamples;
  final List<SelfLearningDeprecatedInterface> deprecatedInterfaces;
  final SelfLearningActivityRetention activityRetention;

  const SelfLearningDashboard({
    required this.summary,
    required this.capabilityAreas,
    required this.activeSideQuests,
    required this.milestones,
    required this.activities,
    required this.learningVelocity,
    required this.resourceUtilization,
    required this.modelEvolution,
    required this.interfaceExamples,
    required this.deprecatedInterfaces,
    required this.activityRetention,
  });

  factory SelfLearningDashboard.fromJson(Map<String, dynamic> json) {
    return SelfLearningDashboard(
      summary: SelfLearningSummary.fromJson(_map(json['summary'])),
      capabilityAreas: _list(json['capabilityAreas'])
          .map(SelfLearningCapabilityArea.fromJson)
          .toList(growable: false),
      activeSideQuests: _list(json['activeSideQuests'])
          .map(SelfLearningSideQuest.fromJson)
          .toList(growable: false),
      milestones: _list(json['milestones'])
          .map(SelfLearningMilestone.fromJson)
          .toList(growable: false),
      activities: _list(json['activities'])
          .map(SelfLearningActivity.fromJson)
          .toList(growable: false),
      learningVelocity:
          SelfLearningVelocity.fromJson(_map(json['learningVelocity'])),
      resourceUtilization: SelfLearningResourceUtilization.fromJson(
          _map(json['resourceUtilization'])),
      modelEvolution:
          SelfLearningModelEvolution.fromJson(_map(json['modelEvolution'])),
      interfaceExamples: _list(json['interfaceExamples'])
          .map(SelfLearningInterfaceExample.fromJson)
          .toList(growable: false),
      deprecatedInterfaces: _list(json['deprecatedInterfaces'])
          .map(SelfLearningDeprecatedInterface.fromJson)
          .toList(growable: false),
      activityRetention: SelfLearningActivityRetention.fromJson(
          _map(json['activityRetention'])),
    );
  }
}

class SelfLearningModelEvolution {
  final String target;
  final String boundary;
  final String status;
  final Map<String, dynamic> metrics;
  final List<SelfLearningModelTrack> tracks;
  final String next;

  const SelfLearningModelEvolution({
    required this.target,
    required this.boundary,
    required this.status,
    required this.metrics,
    required this.tracks,
    required this.next,
  });

  factory SelfLearningModelEvolution.fromJson(Map<String, dynamic> json) {
    return SelfLearningModelEvolution(
      target: json['target']?.toString() ?? '',
      boundary: json['boundary']?.toString() ?? '',
      status: json['status']?.toString() ?? '',
      metrics: _map(json['metrics']),
      tracks: _list(json['tracks'])
          .map(SelfLearningModelTrack.fromJson)
          .toList(growable: false),
      next: json['next']?.toString() ?? '',
    );
  }
}

class SelfLearningModelTrack {
  final String id;
  final String name;
  final String status;
  final String evidence;
  final String next;

  const SelfLearningModelTrack({
    required this.id,
    required this.name,
    required this.status,
    required this.evidence,
    required this.next,
  });

  factory SelfLearningModelTrack.fromJson(Map<String, dynamic> json) {
    return SelfLearningModelTrack(
      id: json['id']?.toString() ?? '',
      name: json['name']?.toString() ?? '',
      status: json['status']?.toString() ?? '',
      evidence: json['evidence']?.toString() ?? '',
      next: json['next']?.toString() ?? '',
    );
  }
}

class SelfLearningVelocity {
  final String status;
  final int targetImprovementCycleMinutes;
  final int recentActivityWindowMinutes;
  final int recentActivityCount;
  final int recentMilestoneWindowHours;
  final int recentMilestoneCount;
  final int maxParallelLearningTasks;
  final String next;

  const SelfLearningVelocity({
    required this.status,
    required this.targetImprovementCycleMinutes,
    required this.recentActivityWindowMinutes,
    required this.recentActivityCount,
    required this.recentMilestoneWindowHours,
    required this.recentMilestoneCount,
    required this.maxParallelLearningTasks,
    required this.next,
  });

  factory SelfLearningVelocity.fromJson(Map<String, dynamic> json) {
    return SelfLearningVelocity(
      status: json['status']?.toString() ?? '',
      targetImprovementCycleMinutes:
          _int(json['targetImprovementCycleMinutes']),
      recentActivityWindowMinutes: _int(json['recentActivityWindowMinutes']),
      recentActivityCount: _int(json['recentActivityCount']),
      recentMilestoneWindowHours: _int(json['recentMilestoneWindowHours']),
      recentMilestoneCount: _int(json['recentMilestoneCount']),
      maxParallelLearningTasks: _int(json['maxParallelLearningTasks']),
      next: json['next']?.toString() ?? '',
    );
  }
}

class SelfLearningResourceUtilization {
  final String mode;
  final bool networkLearningEnabled;
  final bool hardwareAccelerationEnabled;
  final bool preferLocalHardware;
  final int maxParallelLearningTasks;
  final int maxNetworkFetchesPerCycle;
  final int maxCandidateSourcesPerTopic;
  final int maxHardwareUtilizationPercent;
  final List<String> strategy;

  const SelfLearningResourceUtilization({
    required this.mode,
    required this.networkLearningEnabled,
    required this.hardwareAccelerationEnabled,
    required this.preferLocalHardware,
    required this.maxParallelLearningTasks,
    required this.maxNetworkFetchesPerCycle,
    required this.maxCandidateSourcesPerTopic,
    required this.maxHardwareUtilizationPercent,
    required this.strategy,
  });

  factory SelfLearningResourceUtilization.fromJson(Map<String, dynamic> json) {
    return SelfLearningResourceUtilization(
      mode: json['mode']?.toString() ?? '',
      networkLearningEnabled: json['networkLearningEnabled'] == true,
      hardwareAccelerationEnabled: json['hardwareAccelerationEnabled'] == true,
      preferLocalHardware: json['preferLocalHardware'] == true,
      maxParallelLearningTasks: _int(json['maxParallelLearningTasks']),
      maxNetworkFetchesPerCycle: _int(json['maxNetworkFetchesPerCycle']),
      maxCandidateSourcesPerTopic: _int(json['maxCandidateSourcesPerTopic']),
      maxHardwareUtilizationPercent:
          _int(json['maxHardwareUtilizationPercent']),
      strategy: _stringList(json['strategy']),
    );
  }
}

class SelfLearningSummary {
  final String phase;
  final String status;
  final String statusText;
  final bool autoStart;
  final int activityLimit;
  final int okInterfaceCount;
  final int milestoneCount;
  final int activeSideQuestCount;
  final String updatedAt;

  const SelfLearningSummary({
    required this.phase,
    required this.status,
    required this.statusText,
    required this.autoStart,
    required this.activityLimit,
    required this.okInterfaceCount,
    required this.milestoneCount,
    required this.activeSideQuestCount,
    required this.updatedAt,
  });

  factory SelfLearningSummary.fromJson(Map<String, dynamic> json) {
    return SelfLearningSummary(
      phase: json['phase']?.toString() ?? '',
      status: json['status']?.toString() ?? '',
      statusText: json['statusText']?.toString() ?? '',
      autoStart: json['autoStart'] == true,
      activityLimit: _int(json['activityLimit']),
      okInterfaceCount: _int(json['okInterfaceCount']),
      milestoneCount: _int(json['milestoneCount']),
      activeSideQuestCount: _int(json['activeSideQuestCount']),
      updatedAt: json['updatedAt']?.toString() ?? '',
    );
  }
}

class SelfLearningCapabilityArea {
  final String id;
  final String name;
  final String status;
  final String evidence;

  const SelfLearningCapabilityArea({
    required this.id,
    required this.name,
    required this.status,
    required this.evidence,
  });

  factory SelfLearningCapabilityArea.fromJson(Map<String, dynamic> json) {
    return SelfLearningCapabilityArea(
      id: json['id']?.toString() ?? '',
      name: json['name']?.toString() ?? '',
      status: json['status']?.toString() ?? '',
      evidence: json['evidence']?.toString() ?? '',
    );
  }
}

class SelfLearningSideQuest {
  final String id;
  final String title;
  final String status;
  final String objective;
  final String next;
  final String updatedAt;

  const SelfLearningSideQuest({
    required this.id,
    required this.title,
    required this.status,
    required this.objective,
    required this.next,
    required this.updatedAt,
  });

  factory SelfLearningSideQuest.fromJson(Map<String, dynamic> json) {
    return SelfLearningSideQuest(
      id: json['id']?.toString() ?? '',
      title: json['title']?.toString() ?? '',
      status: json['status']?.toString() ?? '',
      objective: json['objective']?.toString() ?? '',
      next: json['next']?.toString() ?? '',
      updatedAt: json['updatedAt']?.toString() ?? '',
    );
  }
}

class SelfLearningMilestone {
  final String id;
  final String category;
  final String status;
  final String title;
  final String evidence;
  final String createdAt;

  const SelfLearningMilestone({
    required this.id,
    required this.category,
    required this.status,
    required this.title,
    required this.evidence,
    required this.createdAt,
  });

  factory SelfLearningMilestone.fromJson(Map<String, dynamic> json) {
    return SelfLearningMilestone(
      id: json['id']?.toString() ?? '',
      category: json['category']?.toString() ?? '',
      status: json['status']?.toString() ?? '',
      title: json['title']?.toString() ?? '',
      evidence: json['evidence']?.toString() ?? '',
      createdAt: json['createdAt']?.toString() ?? '',
    );
  }
}

class SelfLearningActivity {
  final String id;
  final String type;
  final String status;
  final String title;
  final String detail;
  final String source;
  final String evidence;
  final String createdAt;

  const SelfLearningActivity({
    required this.id,
    required this.type,
    required this.status,
    required this.title,
    required this.detail,
    required this.source,
    required this.evidence,
    required this.createdAt,
  });

  factory SelfLearningActivity.fromJson(Map<String, dynamic> json) {
    return SelfLearningActivity(
      id: json['id']?.toString() ?? '',
      type: json['type']?.toString() ?? '',
      status: json['status']?.toString() ?? '',
      title: json['title']?.toString() ?? '',
      detail: json['detail']?.toString() ?? '',
      source: json['source']?.toString() ?? '',
      evidence: json['evidence']?.toString() ?? '',
      createdAt: json['createdAt']?.toString() ?? '',
    );
  }
}

class SelfLearningInterfaceExample {
  final String id;
  final String name;
  final String status;
  final String transport;
  final String method;
  final String endpoint;
  final String providerId;
  final String modelName;
  final Map<String, dynamic> parameterExample;
  final String note;

  const SelfLearningInterfaceExample({
    required this.id,
    required this.name,
    required this.status,
    required this.transport,
    required this.method,
    required this.endpoint,
    required this.providerId,
    required this.modelName,
    required this.parameterExample,
    required this.note,
  });

  factory SelfLearningInterfaceExample.fromJson(Map<String, dynamic> json) {
    return SelfLearningInterfaceExample(
      id: json['id']?.toString() ?? '',
      name: json['name']?.toString() ?? '',
      status: json['status']?.toString() ?? '',
      transport: json['transport']?.toString() ?? '',
      method: json['method']?.toString() ?? '',
      endpoint: json['endpoint']?.toString() ?? '',
      providerId: json['providerId']?.toString() ?? '',
      modelName: json['modelName']?.toString() ?? '',
      parameterExample: _map(json['parameterExample']),
      note: json['note']?.toString() ?? '',
    );
  }
}

class SelfLearningDeprecatedInterface {
  final String id;
  final String name;
  final String status;
  final String reason;
  final String replacement;

  const SelfLearningDeprecatedInterface({
    required this.id,
    required this.name,
    required this.status,
    required this.reason,
    required this.replacement,
  });

  factory SelfLearningDeprecatedInterface.fromJson(Map<String, dynamic> json) {
    return SelfLearningDeprecatedInterface(
      id: json['id']?.toString() ?? '',
      name: json['name']?.toString() ?? '',
      status: json['status']?.toString() ?? '',
      reason: json['reason']?.toString() ?? '',
      replacement: json['replacement']?.toString() ?? '',
    );
  }
}

class SelfLearningActivityRetention {
  final String policy;
  final int backendMaxItems;
  final int returnedItems;
  final int frontendRenderHint;
  final String browsingEventsRule;

  const SelfLearningActivityRetention({
    required this.policy,
    required this.backendMaxItems,
    required this.returnedItems,
    required this.frontendRenderHint,
    required this.browsingEventsRule,
  });

  factory SelfLearningActivityRetention.fromJson(Map<String, dynamic> json) {
    return SelfLearningActivityRetention(
      policy: json['policy']?.toString() ?? '',
      backendMaxItems: _int(json['backendMaxItems']),
      returnedItems: _int(json['returnedItems']),
      frontendRenderHint: _int(json['frontendRenderHint']),
      browsingEventsRule: json['browsingEventsRule']?.toString() ?? '',
    );
  }
}

List<Map<String, dynamic>> _list(Object? value) {
  if (value is List) {
    return value
        .whereType<Map>()
        .map((item) => Map<String, dynamic>.from(item))
        .toList(growable: false);
  }
  return const [];
}

Map<String, dynamic> _map(Object? value) {
  if (value is Map) {
    return Map<String, dynamic>.from(value);
  }
  return <String, dynamic>{};
}

int _int(Object? value) {
  if (value is num) return value.toInt();
  return int.tryParse(value?.toString() ?? '') ?? 0;
}

List<String> _stringList(Object? value) {
  if (value is List) {
    return value.map((item) => item.toString()).toList(growable: false);
  }
  return const [];
}
