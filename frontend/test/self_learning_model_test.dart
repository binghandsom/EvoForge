import 'package:evoforge_web/shared/models/self_learning.dart';
import 'package:flutter_test/flutter_test.dart';

void main() {
  test('parses bounded self-learning dashboard payload', () {
    final dashboard = SelfLearningDashboard.fromJson({
      'summary': {
        'phase': 'autonomous-learning',
        'status': 'active',
        'statusText': 'running',
        'autoStart': true,
        'activityLimit': 80,
        'okInterfaceCount': 1,
        'milestoneCount': 2,
        'activeSideQuestCount': 1,
        'updatedAt': '2026-05-09T00:00:00Z',
      },
      'capabilityAreas': [
        {
          'id': 'reasoning-loop',
          'name': '推理执行闭环',
          'status': 'active',
          'evidence': 'agent.maxSteps=8',
        }
      ],
      'activeSideQuests': [
        {
          'id': 'evoforge.self_learning.startup_sidequest',
          'title': '自我升级多模态模型能力',
          'status': 'active',
          'objective': 'scan capabilities',
          'next': 'verify',
          'updatedAt': '2026-05-09T00:00:00Z',
        }
      ],
      'milestones': [
        {
          'id': 'model:gpt-ok',
          'category': 'model-interface',
          'status': 'OK',
          'title': '模型接口可用',
          'evidence': 'gpt-4.1',
          'createdAt': '2026-05-09T00:00:00Z',
        }
      ],
      'activities': [
        {
          'id': 'knowledge:1',
          'type': 'web-research',
          'status': 'recorded',
          'title': '网页研究',
          'detail': 'research',
          'source': 'browser',
          'evidence': 'https://example.com',
          'createdAt': '2026-05-09T00:00:00Z',
        }
      ],
      'learningVelocity': {
        'status': 'effective',
        'targetImprovementCycleMinutes': 30,
        'recentActivityWindowMinutes': 60,
        'recentActivityCount': 6,
        'recentMilestoneWindowHours': 24,
        'recentMilestoneCount': 2,
        'maxParallelLearningTasks': 4,
        'next': 'keep going',
      },
      'resourceUtilization': {
        'mode': 'adaptive',
        'networkLearningEnabled': true,
        'hardwareAccelerationEnabled': true,
        'preferLocalHardware': true,
        'maxParallelLearningTasks': 4,
        'maxNetworkFetchesPerCycle': 16,
        'maxCandidateSourcesPerTopic': 8,
        'maxHardwareUtilizationPercent': 85,
        'strategy': ['parallel search', 'local cache'],
      },
      'modelEvolution': {
        'target': 'ChatGPT-like reasoning model with multimodal generation',
        'boundary': '知识库、记忆和 Skill 是训练数据/工具环境/脚手架，不是模型本体。',
        'status': 'bootstrapping-with-external-models',
        'metrics': {
          'reasoningTraceCount': 7,
          'supervisedSampleCandidates': 3,
          'okReasoningBackends': 1,
          'multimodalGenerationBackends': 1,
        },
        'tracks': [
          {
            'id': 'reasoning-model',
            'name': '推理模型',
            'status': 'distillation-ready',
            'evidence': '1 个可用 LLM 后端',
            'next': '建立评测集',
          },
          {
            'id': 'multimodal-generation',
            'name': '多模态生成',
            'status': 'interface-ready',
            'evidence': '1 个生成后端',
            'next': '接入质量评测',
          },
        ],
        'next': '建立训练样本格式',
      },
      'interfaceExamples': [
        {
          'id': 'self-learning-dashboard',
          'name': '自我学习仪表盘',
          'status': 'OK',
          'transport': 'client_request / REST',
          'method': 'selfLearning.dashboard',
          'endpoint': '/api/self-learning/dashboard',
          'parameterExample': {'limit': 80},
        }
      ],
      'deprecatedInterfaces': [
        {
          'id': 'legacy-completions',
          'name': '纯 text completion 旧接口',
          'status': 'abandoned',
          'reason': 'not enough',
          'replacement': 'chat',
        }
      ],
      'activityRetention': {
        'policy': 'newest-first-bounded-stream',
        'backendMaxItems': 80,
        'returnedItems': 1,
        'frontendRenderHint': 80,
        'browsingEventsRule': 'bounded',
      },
    });

    expect(dashboard.summary.autoStart, isTrue);
    expect(dashboard.capabilityAreas.single.id, 'reasoning-loop');
    expect(dashboard.activeSideQuests.single.status, 'active');
    expect(dashboard.activities.single.type, 'web-research');
    expect(dashboard.learningVelocity.recentActivityCount, 6);
    expect(dashboard.resourceUtilization.maxNetworkFetchesPerCycle, 16);
    expect(dashboard.resourceUtilization.strategy.length, 2);
    expect(dashboard.modelEvolution.boundary, contains('不是模型本体'));
    expect(dashboard.modelEvolution.tracks.last.id, 'multimodal-generation');
    expect(dashboard.modelEvolution.metrics['reasoningTraceCount'], 7);
    expect(dashboard.interfaceExamples.single.parameterExample['limit'], 80);
    expect(dashboard.deprecatedInterfaces.single.status, 'abandoned');
    expect(dashboard.activityRetention.backendMaxItems, 80);
  });
}
