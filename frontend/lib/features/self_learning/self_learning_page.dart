import 'dart:async';
import 'dart:convert';

import 'package:flutter/material.dart';

import '../../shared/api/evoforge_api.dart';
import '../../shared/models/self_learning.dart';
import '../../shared/widgets/page_header.dart';

class SelfLearningPage extends StatefulWidget {
  final EvoForgeApi api;

  const SelfLearningPage({super.key, required this.api});

  @override
  State<SelfLearningPage> createState() => _SelfLearningPageState();
}

class _SelfLearningPageState extends State<SelfLearningPage> {
  static const int activityRenderLimit = 80;

  SelfLearningDashboard? dashboard;
  Timer? poller;
  bool loading = false;
  bool autoRefresh = true;
  String error = '';

  @override
  void initState() {
    super.initState();
    loadDashboard();
    poller = Timer.periodic(const Duration(seconds: 12), (_) {
      if (autoRefresh) loadDashboard(silent: true);
    });
  }

  @override
  void dispose() {
    poller?.cancel();
    super.dispose();
  }

  Future<void> loadDashboard({bool silent = false}) async {
    if (loading && silent) return;
    if (!silent && mounted) {
      setState(() {
        loading = true;
        error = '';
      });
    }
    try {
      final data = await widget.api.loadSelfLearningDashboard(
        limit: activityRenderLimit,
      );
      if (!mounted) return;
      setState(() {
        dashboard = _boundedDashboard(data);
        error = '';
      });
    } catch (e) {
      if (!mounted) return;
      setState(() => error = e.toString());
    } finally {
      if (mounted && !silent) setState(() => loading = false);
    }
  }

  SelfLearningDashboard _boundedDashboard(SelfLearningDashboard value) {
    return SelfLearningDashboard(
      summary: value.summary,
      capabilityAreas: value.capabilityAreas,
      activeSideQuests: value.activeSideQuests,
      milestones: value.milestones,
      activities:
          value.activities.take(activityRenderLimit).toList(growable: false),
      learningVelocity: value.learningVelocity,
      resourceUtilization: value.resourceUtilization,
      modelEvolution: value.modelEvolution,
      interfaceExamples: value.interfaceExamples,
      deprecatedInterfaces: value.deprecatedInterfaces,
      activityRetention: value.activityRetention,
    );
  }

  @override
  Widget build(BuildContext context) {
    final data = dashboard;
    return Column(
      children: [
        PageHeader(
          title: '自我学习',
          subtitle: data?.summary.statusText ?? '自主升级进展',
          actions: [
            IconButton(
              onPressed: () => setState(() => autoRefresh = !autoRefresh),
              icon: Icon(autoRefresh ? Icons.pause_circle : Icons.play_circle),
              tooltip: autoRefresh ? '暂停刷新' : '继续刷新',
            ),
            IconButton(
              onPressed: loading ? null : () => loadDashboard(),
              icon: const Icon(Icons.refresh),
              tooltip: '刷新进展',
            ),
          ],
        ),
        if (loading) const LinearProgressIndicator(),
        if (error.isNotEmpty)
          Padding(
            padding: const EdgeInsets.fromLTRB(24, 0, 24, 12),
            child: _ErrorBanner(message: error),
          ),
        Expanded(
          child: data == null
              ? const Center(child: Text('加载中'))
              : LayoutBuilder(
                  builder: (context, constraints) {
                    final narrow = constraints.maxWidth < 980;
                    final main = _MainLearningContent(data: data);
                    if (narrow) {
                      return ListView(
                        padding: const EdgeInsets.fromLTRB(16, 4, 16, 24),
                        children: [
                          main,
                          const SizedBox(height: 12),
                          SizedBox(
                            height: 560,
                            child: _ActivityPanel(data: data),
                          ),
                        ],
                      );
                    }
                    return Row(
                      crossAxisAlignment: CrossAxisAlignment.stretch,
                      children: [
                        Expanded(
                          child: ListView(
                            padding: const EdgeInsets.fromLTRB(24, 4, 16, 24),
                            children: [main],
                          ),
                        ),
                        const VerticalDivider(width: 1),
                        SizedBox(
                          width: 430,
                          child: Padding(
                            padding: const EdgeInsets.fromLTRB(16, 4, 24, 24),
                            child: _ActivityPanel(data: data),
                          ),
                        ),
                      ],
                    );
                  },
                ),
        ),
      ],
    );
  }
}

class _MainLearningContent extends StatelessWidget {
  final SelfLearningDashboard data;

  const _MainLearningContent({required this.data});

  @override
  Widget build(BuildContext context) {
    return Column(
      crossAxisAlignment: CrossAxisAlignment.stretch,
      children: [
        _SummaryStrip(data: data),
        const SizedBox(height: 12),
        _VelocityResourcePanel(data: data),
        const SizedBox(height: 18),
        _ModelEvolutionPanel(data: data),
        const SizedBox(height: 18),
        _SectionTitle(
          title: '能力图谱',
          trailing: _StatusChip(label: data.summary.status),
        ),
        const SizedBox(height: 8),
        _CapabilityGrid(items: data.capabilityAreas),
        const SizedBox(height: 18),
        _SectionTitle(
          title: '学习侧线',
          trailing: Text('${data.activeSideQuests.length} 条'),
        ),
        const SizedBox(height: 8),
        _SideQuestList(items: data.activeSideQuests),
        const SizedBox(height: 18),
        _SectionTitle(
          title: '有效里程碑',
          trailing: Text('${data.milestones.length} 条'),
        ),
        const SizedBox(height: 8),
        _MilestoneList(items: data.milestones),
        const SizedBox(height: 18),
        _SectionTitle(
          title: 'OK 接口',
          trailing: Text('${data.interfaceExamples.length} 个'),
        ),
        const SizedBox(height: 8),
        _InterfaceExamples(items: data.interfaceExamples),
        const SizedBox(height: 18),
        _SectionTitle(
          title: '已摒弃接口',
          trailing: Text('${data.deprecatedInterfaces.length} 个'),
        ),
        const SizedBox(height: 8),
        _DeprecatedInterfaces(items: data.deprecatedInterfaces),
      ],
    );
  }
}

class _SummaryStrip extends StatelessWidget {
  final SelfLearningDashboard data;

  const _SummaryStrip({required this.data});

  @override
  Widget build(BuildContext context) {
    final summary = data.summary;
    return Wrap(
      spacing: 10,
      runSpacing: 10,
      children: [
        _MetricTile(
          label: 'OK 接口',
          value: summary.okInterfaceCount.toString(),
          icon: Icons.api,
          color: Colors.teal,
        ),
        _MetricTile(
          label: '有效里程碑',
          value: summary.milestoneCount.toString(),
          icon: Icons.flag,
          color: Colors.indigo,
        ),
        _MetricTile(
          label: '学习侧线',
          value: summary.activeSideQuestCount.toString(),
          icon: Icons.route,
          color: Colors.deepOrange,
        ),
        _MetricTile(
          label: '活动窗口',
          value: data.activityRetention.returnedItems.toString(),
          icon: Icons.stream,
          color: Colors.blueGrey,
        ),
        _MetricTile(
          label: '目标周期',
          value: '${data.learningVelocity.targetImprovementCycleMinutes}m',
          icon: Icons.speed,
          color: Colors.green,
        ),
        _MetricTile(
          label: '并行任务',
          value: data.resourceUtilization.maxParallelLearningTasks.toString(),
          icon: Icons.account_tree,
          color: Colors.purple,
        ),
        _MetricTile(
          label: '模型轨道',
          value: data.modelEvolution.tracks.length.toString(),
          icon: Icons.model_training,
          color: Colors.cyan,
        ),
      ],
    );
  }
}

class _ModelEvolutionPanel extends StatelessWidget {
  final SelfLearningDashboard data;

  const _ModelEvolutionPanel({required this.data});

  @override
  Widget build(BuildContext context) {
    final model = data.modelEvolution;
    return Material(
      color: Theme.of(context).colorScheme.surface,
      shape: RoundedRectangleBorder(
        side: BorderSide(color: Theme.of(context).dividerColor),
        borderRadius: BorderRadius.circular(8),
      ),
      child: Padding(
        padding: const EdgeInsets.all(14),
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            Row(
              children: [
                const Icon(Icons.model_training),
                const SizedBox(width: 8),
                Expanded(
                  child: Text(
                    '模型本体进化',
                    style: Theme.of(context).textTheme.titleMedium?.copyWith(
                          fontWeight: FontWeight.w700,
                        ),
                  ),
                ),
                _StatusChip(label: model.status),
              ],
            ),
            const SizedBox(height: 8),
            Text(
              model.boundary,
              maxLines: 2,
              overflow: TextOverflow.ellipsis,
              style: Theme.of(context).textTheme.bodySmall,
            ),
            const SizedBox(height: 10),
            Wrap(
              spacing: 8,
              runSpacing: 8,
              children: [
                _CategoryPill(
                    label: '轨迹 ${model.metrics['reasoningTraceCount'] ?? 0}'),
                _CategoryPill(
                    label:
                        '样本 ${model.metrics['supervisedSampleCandidates'] ?? 0}'),
                _CategoryPill(
                    label: 'LLM ${model.metrics['okReasoningBackends'] ?? 0}'),
                _CategoryPill(
                    label:
                        '生成 ${model.metrics['multimodalGenerationBackends'] ?? 0}'),
              ],
            ),
            const SizedBox(height: 10),
            Wrap(
              spacing: 10,
              runSpacing: 10,
              children: model.tracks.map((track) {
                return ConstrainedBox(
                  constraints:
                      const BoxConstraints(minWidth: 220, maxWidth: 340),
                  child: _ModelTrackTile(track: track),
                );
              }).toList(growable: false),
            ),
            if (model.next.isNotEmpty) ...[
              const SizedBox(height: 10),
              Text(
                model.next,
                maxLines: 2,
                overflow: TextOverflow.ellipsis,
                style: Theme.of(context).textTheme.bodySmall,
              ),
            ],
          ],
        ),
      ),
    );
  }
}

class _ModelTrackTile extends StatelessWidget {
  final SelfLearningModelTrack track;

  const _ModelTrackTile({required this.track});

  @override
  Widget build(BuildContext context) {
    return Container(
      padding: const EdgeInsets.all(12),
      decoration: BoxDecoration(
        border: Border.all(color: Theme.of(context).dividerColor),
        borderRadius: BorderRadius.circular(8),
      ),
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          Row(
            children: [
              Expanded(
                child: Text(
                  track.name,
                  maxLines: 1,
                  overflow: TextOverflow.ellipsis,
                  style: Theme.of(context).textTheme.titleSmall,
                ),
              ),
              _StatusChip(label: track.status),
            ],
          ),
          const SizedBox(height: 8),
          Text(
            track.evidence,
            maxLines: 2,
            overflow: TextOverflow.ellipsis,
            style: Theme.of(context).textTheme.bodySmall,
          ),
        ],
      ),
    );
  }
}

class _VelocityResourcePanel extends StatelessWidget {
  final SelfLearningDashboard data;

  const _VelocityResourcePanel({required this.data});

  @override
  Widget build(BuildContext context) {
    final velocity = data.learningVelocity;
    final resource = data.resourceUtilization;
    return Material(
      color: Theme.of(context).colorScheme.surface,
      shape: RoundedRectangleBorder(
        side: BorderSide(color: Theme.of(context).dividerColor),
        borderRadius: BorderRadius.circular(8),
      ),
      child: Padding(
        padding: const EdgeInsets.all(14),
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            Row(
              children: [
                const Icon(Icons.bolt),
                const SizedBox(width: 8),
                Expanded(
                  child: Text(
                    '速度与资源',
                    style: Theme.of(context).textTheme.titleMedium?.copyWith(
                          fontWeight: FontWeight.w700,
                        ),
                  ),
                ),
                _StatusChip(label: velocity.status),
              ],
            ),
            const SizedBox(height: 10),
            Wrap(
              spacing: 8,
              runSpacing: 8,
              children: [
                _CategoryPill(
                    label:
                        '活动 ${velocity.recentActivityCount}/${velocity.recentActivityWindowMinutes}m'),
                _CategoryPill(
                    label:
                        '里程碑 ${velocity.recentMilestoneCount}/${velocity.recentMilestoneWindowHours}h'),
                _CategoryPill(
                    label: '网络 ${resource.maxNetworkFetchesPerCycle}/cycle'),
                _CategoryPill(
                    label: '来源 ${resource.maxCandidateSourcesPerTopic}/topic'),
                _CategoryPill(
                    label: '硬件 ${resource.maxHardwareUtilizationPercent}%'),
                _CategoryPill(label: resource.mode),
              ],
            ),
            if (velocity.next.isNotEmpty) ...[
              const SizedBox(height: 10),
              Text(
                velocity.next,
                maxLines: 2,
                overflow: TextOverflow.ellipsis,
                style: Theme.of(context).textTheme.bodySmall,
              ),
            ],
          ],
        ),
      ),
    );
  }
}

class _MetricTile extends StatelessWidget {
  final String label;
  final String value;
  final IconData icon;
  final MaterialColor color;

  const _MetricTile({
    required this.label,
    required this.value,
    required this.icon,
    required this.color,
  });

  @override
  Widget build(BuildContext context) {
    final scheme = Theme.of(context).colorScheme;
    return ConstrainedBox(
      constraints: const BoxConstraints(minWidth: 150, maxWidth: 210),
      child: Material(
        color: scheme.surface,
        shape: RoundedRectangleBorder(
          side: BorderSide(color: Theme.of(context).dividerColor),
          borderRadius: BorderRadius.circular(8),
        ),
        child: Padding(
          padding: const EdgeInsets.all(14),
          child: Row(
            children: [
              Icon(icon, color: color.shade600),
              const SizedBox(width: 10),
              Expanded(
                child: Column(
                  crossAxisAlignment: CrossAxisAlignment.start,
                  children: [
                    Text(
                      value,
                      style: Theme.of(context).textTheme.titleLarge?.copyWith(
                            fontWeight: FontWeight.w700,
                          ),
                    ),
                    Text(
                      label,
                      maxLines: 1,
                      overflow: TextOverflow.ellipsis,
                      style: Theme.of(context).textTheme.bodySmall,
                    ),
                  ],
                ),
              ),
            ],
          ),
        ),
      ),
    );
  }
}

class _CapabilityGrid extends StatelessWidget {
  final List<SelfLearningCapabilityArea> items;

  const _CapabilityGrid({required this.items});

  @override
  Widget build(BuildContext context) {
    if (items.isEmpty) return const _EmptyPanel(text: '暂无能力记录');
    return Wrap(
      spacing: 10,
      runSpacing: 10,
      children: items.map((item) {
        return ConstrainedBox(
          constraints: const BoxConstraints(minWidth: 220, maxWidth: 340),
          child: Material(
            color: Theme.of(context).colorScheme.surface,
            shape: RoundedRectangleBorder(
              side: BorderSide(color: Theme.of(context).dividerColor),
              borderRadius: BorderRadius.circular(8),
            ),
            child: Padding(
              padding: const EdgeInsets.all(14),
              child: Column(
                crossAxisAlignment: CrossAxisAlignment.start,
                children: [
                  Row(
                    children: [
                      Expanded(
                        child: Text(
                          item.name,
                          maxLines: 1,
                          overflow: TextOverflow.ellipsis,
                          style: Theme.of(context).textTheme.titleSmall,
                        ),
                      ),
                      _StatusChip(label: item.status),
                    ],
                  ),
                  const SizedBox(height: 8),
                  Text(
                    item.evidence,
                    maxLines: 3,
                    overflow: TextOverflow.ellipsis,
                    style: Theme.of(context).textTheme.bodySmall,
                  ),
                ],
              ),
            ),
          ),
        );
      }).toList(growable: false),
    );
  }
}

class _SideQuestList extends StatelessWidget {
  final List<SelfLearningSideQuest> items;

  const _SideQuestList({required this.items});

  @override
  Widget build(BuildContext context) {
    if (items.isEmpty) return const _EmptyPanel(text: '暂无学习侧线');
    return Column(
      children: items.map((item) {
        return Padding(
          padding: const EdgeInsets.only(bottom: 8),
          child: Material(
            color: Theme.of(context).colorScheme.surface,
            shape: RoundedRectangleBorder(
              side: BorderSide(color: Theme.of(context).dividerColor),
              borderRadius: BorderRadius.circular(8),
            ),
            child: ListTile(
              leading: Icon(
                Icons.auto_awesome_motion,
                color: statusColor(item.status, Theme.of(context).colorScheme),
              ),
              title: Text(item.title,
                  maxLines: 1, overflow: TextOverflow.ellipsis),
              subtitle: Text(
                item.next.isEmpty ? item.objective : item.next,
                maxLines: 2,
                overflow: TextOverflow.ellipsis,
              ),
              trailing: _StatusChip(label: item.status),
            ),
          ),
        );
      }).toList(growable: false),
    );
  }
}

class _MilestoneList extends StatelessWidget {
  final List<SelfLearningMilestone> items;

  const _MilestoneList({required this.items});

  @override
  Widget build(BuildContext context) {
    if (items.isEmpty) return const _EmptyPanel(text: '暂无有效里程碑');
    return Column(
      children: items.map((item) {
        return Padding(
          padding: const EdgeInsets.only(bottom: 8),
          child: Material(
            color: Theme.of(context).colorScheme.surface,
            shape: RoundedRectangleBorder(
              side: BorderSide(color: Theme.of(context).dividerColor),
              borderRadius: BorderRadius.circular(8),
            ),
            child: ListTile(
              leading: Icon(
                Icons.verified,
                color: statusColor(item.status, Theme.of(context).colorScheme),
              ),
              title: Text(item.title,
                  maxLines: 1, overflow: TextOverflow.ellipsis),
              subtitle: Text(
                item.evidence,
                maxLines: 2,
                overflow: TextOverflow.ellipsis,
              ),
              trailing: _CategoryPill(label: item.category),
            ),
          ),
        );
      }).toList(growable: false),
    );
  }
}

class _InterfaceExamples extends StatelessWidget {
  final List<SelfLearningInterfaceExample> items;

  const _InterfaceExamples({required this.items});

  @override
  Widget build(BuildContext context) {
    if (items.isEmpty) return const _EmptyPanel(text: '暂无 OK 接口');
    return Column(
      children: items.map((item) {
        return Padding(
          padding: const EdgeInsets.only(bottom: 10),
          child: Material(
            color: Theme.of(context).colorScheme.surface,
            shape: RoundedRectangleBorder(
              side: BorderSide(color: Theme.of(context).dividerColor),
              borderRadius: BorderRadius.circular(8),
            ),
            child: Padding(
              padding: const EdgeInsets.all(14),
              child: Column(
                crossAxisAlignment: CrossAxisAlignment.start,
                children: [
                  Row(
                    children: [
                      Icon(Icons.check_circle, color: Colors.teal.shade600),
                      const SizedBox(width: 8),
                      Expanded(
                        child: Text(
                          item.name,
                          maxLines: 1,
                          overflow: TextOverflow.ellipsis,
                          style: Theme.of(context).textTheme.titleSmall,
                        ),
                      ),
                      _StatusChip(label: item.status),
                    ],
                  ),
                  const SizedBox(height: 8),
                  Wrap(
                    spacing: 6,
                    runSpacing: 6,
                    children: [
                      _CategoryPill(label: item.transport),
                      _CategoryPill(label: item.method),
                      _CategoryPill(label: item.endpoint),
                    ],
                  ),
                  const SizedBox(height: 10),
                  _CodeBlock(text: _prettyJson(item.parameterExample)),
                  if (item.note.isNotEmpty) ...[
                    const SizedBox(height: 8),
                    Text(
                      item.note,
                      maxLines: 2,
                      overflow: TextOverflow.ellipsis,
                      style: Theme.of(context).textTheme.bodySmall,
                    ),
                  ],
                ],
              ),
            ),
          ),
        );
      }).toList(growable: false),
    );
  }
}

class _DeprecatedInterfaces extends StatelessWidget {
  final List<SelfLearningDeprecatedInterface> items;

  const _DeprecatedInterfaces({required this.items});

  @override
  Widget build(BuildContext context) {
    if (items.isEmpty) return const _EmptyPanel(text: '暂无摒弃记录');
    return Column(
      children: items.map((item) {
        return Padding(
          padding: const EdgeInsets.only(bottom: 8),
          child: Material(
            color: Theme.of(context).colorScheme.surface,
            shape: RoundedRectangleBorder(
              side: BorderSide(color: Theme.of(context).dividerColor),
              borderRadius: BorderRadius.circular(8),
            ),
            child: ListTile(
              leading: Icon(Icons.block, color: Colors.red.shade600),
              title:
                  Text(item.name, maxLines: 1, overflow: TextOverflow.ellipsis),
              subtitle: Text(
                '${item.reason} 替代：${item.replacement}',
                maxLines: 3,
                overflow: TextOverflow.ellipsis,
              ),
              trailing: _StatusChip(label: item.status),
            ),
          ),
        );
      }).toList(growable: false),
    );
  }
}

class _ActivityPanel extends StatelessWidget {
  final SelfLearningDashboard data;

  const _ActivityPanel({required this.data});

  @override
  Widget build(BuildContext context) {
    final activities = data.activities;
    return Material(
      color: Theme.of(context).colorScheme.surface,
      shape: RoundedRectangleBorder(
        side: BorderSide(color: Theme.of(context).dividerColor),
        borderRadius: BorderRadius.circular(8),
      ),
      child: Column(
        children: [
          Padding(
            padding: const EdgeInsets.fromLTRB(14, 12, 14, 8),
            child: Row(
              children: [
                const Icon(Icons.stream),
                const SizedBox(width: 8),
                Expanded(
                  child: Text(
                    '活动流',
                    style: Theme.of(context).textTheme.titleMedium,
                  ),
                ),
                _CategoryPill(
                  label:
                      '${data.activityRetention.returnedItems}/${data.activityRetention.backendMaxItems}',
                ),
              ],
            ),
          ),
          const Divider(height: 1),
          Expanded(
            child: activities.isEmpty
                ? const Center(child: Text('暂无活动'))
                : ListView.separated(
                    padding: const EdgeInsets.all(10),
                    itemCount: activities.length,
                    separatorBuilder: (_, __) => const SizedBox(height: 8),
                    itemBuilder: (context, index) {
                      final item = activities[index];
                      return _ActivityTile(item: item);
                    },
                  ),
          ),
        ],
      ),
    );
  }
}

class _ActivityTile extends StatelessWidget {
  final SelfLearningActivity item;

  const _ActivityTile({required this.item});

  @override
  Widget build(BuildContext context) {
    final scheme = Theme.of(context).colorScheme;
    return Material(
      color: scheme.surfaceContainerHighest.withValues(alpha: 0.34),
      borderRadius: BorderRadius.circular(8),
      child: Padding(
        padding: const EdgeInsets.all(10),
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            Row(
              children: [
                Icon(
                  activityIcon(item.type),
                  size: 18,
                  color: statusColor(item.status, scheme),
                ),
                const SizedBox(width: 8),
                Expanded(
                  child: Text(
                    item.title,
                    maxLines: 1,
                    overflow: TextOverflow.ellipsis,
                    style: Theme.of(context).textTheme.titleSmall,
                  ),
                ),
              ],
            ),
            if (item.detail.isNotEmpty) ...[
              const SizedBox(height: 6),
              Text(
                item.detail,
                maxLines: 3,
                overflow: TextOverflow.ellipsis,
                style: Theme.of(context).textTheme.bodySmall,
              ),
            ],
            const SizedBox(height: 8),
            Wrap(
              spacing: 6,
              runSpacing: 6,
              children: [
                _CategoryPill(label: item.type),
                _StatusChip(label: item.status),
                if (item.source.isNotEmpty) _CategoryPill(label: item.source),
              ],
            ),
          ],
        ),
      ),
    );
  }
}

class _SectionTitle extends StatelessWidget {
  final String title;
  final Widget trailing;

  const _SectionTitle({required this.title, required this.trailing});

  @override
  Widget build(BuildContext context) {
    return Row(
      children: [
        Expanded(
          child: Text(
            title,
            style: Theme.of(context).textTheme.titleMedium?.copyWith(
                  fontWeight: FontWeight.w700,
                ),
          ),
        ),
        DefaultTextStyle.merge(
          style: Theme.of(context).textTheme.bodySmall,
          child: trailing,
        ),
      ],
    );
  }
}

class _StatusChip extends StatelessWidget {
  final String label;

  const _StatusChip({required this.label});

  @override
  Widget build(BuildContext context) {
    final scheme = Theme.of(context).colorScheme;
    final color = statusColor(label, scheme);
    return Chip(
      label: Text(label.isEmpty ? '-' : label),
      visualDensity: VisualDensity.compact,
      materialTapTargetSize: MaterialTapTargetSize.shrinkWrap,
      side: BorderSide(color: color.withValues(alpha: 0.5)),
      backgroundColor: color.withValues(alpha: 0.12),
      labelStyle: TextStyle(color: color, fontSize: 12),
    );
  }
}

class _CategoryPill extends StatelessWidget {
  final String label;

  const _CategoryPill({required this.label});

  @override
  Widget build(BuildContext context) {
    return Container(
      padding: const EdgeInsets.symmetric(horizontal: 8, vertical: 4),
      decoration: BoxDecoration(
        color: Theme.of(context).colorScheme.surfaceContainerHighest,
        borderRadius: BorderRadius.circular(8),
      ),
      child: Text(
        label.isEmpty ? '-' : label,
        maxLines: 1,
        overflow: TextOverflow.ellipsis,
        style: Theme.of(context).textTheme.labelSmall,
      ),
    );
  }
}

class _CodeBlock extends StatelessWidget {
  final String text;

  const _CodeBlock({required this.text});

  @override
  Widget build(BuildContext context) {
    return Container(
      width: double.infinity,
      constraints: const BoxConstraints(minHeight: 44),
      padding: const EdgeInsets.all(10),
      decoration: BoxDecoration(
        color: Colors.black.withValues(alpha: 0.04),
        borderRadius: BorderRadius.circular(8),
        border: Border.all(color: Theme.of(context).dividerColor),
      ),
      child: SingleChildScrollView(
        scrollDirection: Axis.horizontal,
        child: SelectableText(
          text,
          style: const TextStyle(
            fontFamily: 'monospace',
            fontSize: 12,
            height: 1.35,
          ),
        ),
      ),
    );
  }
}

class _EmptyPanel extends StatelessWidget {
  final String text;

  const _EmptyPanel({required this.text});

  @override
  Widget build(BuildContext context) {
    return Material(
      color: Theme.of(context).colorScheme.surface,
      shape: RoundedRectangleBorder(
        side: BorderSide(color: Theme.of(context).dividerColor),
        borderRadius: BorderRadius.circular(8),
      ),
      child: Padding(
        padding: const EdgeInsets.all(18),
        child: Center(child: Text(text)),
      ),
    );
  }
}

class _ErrorBanner extends StatelessWidget {
  final String message;

  const _ErrorBanner({required this.message});

  @override
  Widget build(BuildContext context) {
    return Material(
      color: Theme.of(context).colorScheme.errorContainer,
      borderRadius: BorderRadius.circular(8),
      child: Padding(
        padding: const EdgeInsets.all(12),
        child: Row(
          children: [
            Icon(Icons.error_outline,
                color: Theme.of(context).colorScheme.error),
            const SizedBox(width: 10),
            Expanded(
              child: Text(
                message,
                maxLines: 3,
                overflow: TextOverflow.ellipsis,
              ),
            ),
          ],
        ),
      ),
    );
  }
}

Color statusColor(String status, ColorScheme scheme) {
  final normalized = status.toLowerCase();
  if (normalized.contains('ok') ||
      normalized.contains('active') ||
      normalized.contains('completed') ||
      normalized.contains('success')) {
    return Colors.teal.shade700;
  }
  if (normalized.contains('failed') ||
      normalized.contains('error') ||
      normalized.contains('abandoned')) {
    return scheme.error;
  }
  if (normalized.contains('need') || normalized.contains('warming')) {
    return Colors.orange.shade800;
  }
  return scheme.primary;
}

IconData activityIcon(String type) {
  switch (type) {
    case 'web-research':
      return Icons.public;
    case 'skill-audit':
      return Icons.auto_fix_high;
    case 'model-interface':
      return Icons.api;
    case 'validated-change':
      return Icons.verified;
    case 'side-quest':
      return Icons.route;
    default:
      return Icons.notes;
  }
}

String _prettyJson(Map<String, dynamic> value) {
  if (value.isEmpty) return '{}';
  return const JsonEncoder.withIndent('  ').convert(value);
}
