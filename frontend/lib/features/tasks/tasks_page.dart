import 'package:flutter/material.dart';

import '../../shared/api/evoforge_api.dart';
import '../../shared/models/device_task_event.dart';
import '../../shared/models/device_protocol.dart';
import '../../shared/models/device_task_summary.dart';
import '../../shared/widgets/page_header.dart';

class TasksPage extends StatefulWidget {
  final EvoForgeApi api;

  const TasksPage({super.key, required this.api});

  @override
  State<TasksPage> createState() => _TasksPageState();
}

class _TasksPageState extends State<TasksPage> {
  final TextEditingController taskIdController = TextEditingController();
  List<DeviceTaskSummary> tasks = [];
  List<DeviceTaskEvent> events = [];
  String selectedTaskId = '';
  bool loadingTasks = false;
  bool loadingEvents = false;
  bool deciding = false;

  @override
  void initState() {
    super.initState();
    loadRecentTasks();
  }

  @override
  void dispose() {
    taskIdController.dispose();
    super.dispose();
  }

  Future<void> loadRecentTasks() async {
    if (loadingTasks) return;
    setState(() => loadingTasks = true);
    try {
      final data = await widget.api.loadRecentTasks();
      if (!mounted) return;
      final nextSelection = selectedTaskId.isNotEmpty
          ? selectedTaskId
          : data.isEmpty
              ? ''
              : data.first.taskId;
      setState(() {
        tasks = data;
        selectedTaskId = nextSelection;
        taskIdController.text = nextSelection;
      });
      if (nextSelection.isNotEmpty && events.isEmpty) {
        await loadEvents(nextSelection);
      }
    } catch (e) {
      showMessage('任务加载失败: $e');
    } finally {
      if (mounted) setState(() => loadingTasks = false);
    }
  }

  Future<void> loadEvents([String? taskId]) async {
    final targetTaskId = (taskId ?? taskIdController.text).trim();
    if (targetTaskId.isEmpty) {
      showMessage('请输入 Task ID');
      return;
    }
    setState(() {
      loadingEvents = true;
      selectedTaskId = targetTaskId;
      taskIdController.text = targetTaskId;
    });
    try {
      final data = await widget.api.loadTaskEvents(targetTaskId);
      if (mounted) setState(() => events = data);
    } catch (e) {
      showMessage('事件加载失败: $e');
    } finally {
      if (mounted) setState(() => loadingEvents = false);
    }
  }

  void selectTask(DeviceTaskSummary task) {
    loadEvents(task.taskId);
  }

  Future<void> decideSelectedTask({required bool approve}) async {
    if (selectedTaskId.isEmpty || deciding) return;
    setState(() => deciding = true);
    try {
      if (approve) {
        await widget.api.approveTask(selectedTaskId);
      } else {
        await widget.api.rejectTask(selectedTaskId);
      }
      await loadEvents(selectedTaskId);
      await loadRecentTasks();
    } catch (e) {
      showMessage('${approve ? '批准' : '拒绝'}失败: $e');
    } finally {
      if (mounted) setState(() => deciding = false);
    }
  }

  void showMessage(String message) {
    if (!mounted) return;
    ScaffoldMessenger.of(
      context,
    ).showSnackBar(SnackBar(content: Text(message)));
  }

  @override
  Widget build(BuildContext context) {
    return Column(
      children: [
        PageHeader(
          title: '任务',
          subtitle: '查看远程执行事件流',
          actions: [
            IconButton(
              onPressed: loadingTasks ? null : loadRecentTasks,
              icon: const Icon(Icons.refresh),
              tooltip: '刷新任务',
            ),
          ],
        ),
        if (loadingTasks || loadingEvents) const LinearProgressIndicator(),
        Expanded(
          child: LayoutBuilder(
            builder: (context, constraints) {
              final narrow = constraints.maxWidth < 860;
              if (narrow) {
                return ListView(
                  padding: const EdgeInsets.fromLTRB(16, 4, 16, 16),
                  children: [
                    SizedBox(
                      height: 300,
                      child: _TaskList(
                        tasks: tasks,
                        selectedTaskId: selectedTaskId,
                        loading: loadingTasks,
                        onSelect: selectTask,
                      ),
                    ),
                    const SizedBox(height: 12),
                    _TaskEventsPanel(
                      controller: taskIdController,
                      events: events,
                      selectedTaskId: selectedTaskId,
                      loading: loadingEvents,
                      scrollable: false,
                      deciding: deciding,
                      onApprove: latestNeedsApproval
                          ? () => decideSelectedTask(approve: true)
                          : null,
                      onReject: latestNeedsApproval
                          ? () => decideSelectedTask(approve: false)
                          : null,
                      onSearch: () => loadEvents(),
                    ),
                  ],
                );
              }
              return Row(
                children: [
                  SizedBox(
                    width: 380,
                    child: Padding(
                      padding: const EdgeInsets.fromLTRB(24, 4, 12, 24),
                      child: _TaskList(
                        tasks: tasks,
                        selectedTaskId: selectedTaskId,
                        loading: loadingTasks,
                        onSelect: selectTask,
                      ),
                    ),
                  ),
                  const VerticalDivider(width: 1),
                  Expanded(
                    child: Padding(
                      padding: const EdgeInsets.fromLTRB(16, 4, 24, 24),
                      child: _TaskEventsPanel(
                        controller: taskIdController,
                        events: events,
                        selectedTaskId: selectedTaskId,
                        loading: loadingEvents,
                        scrollable: true,
                        deciding: deciding,
                        onApprove: latestNeedsApproval
                            ? () => decideSelectedTask(approve: true)
                            : null,
                        onReject: latestNeedsApproval
                            ? () => decideSelectedTask(approve: false)
                            : null,
                        onSearch: () => loadEvents(),
                      ),
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

  bool get latestNeedsApproval {
    return events.isNotEmpty &&
        DeviceTaskStatus.needsApprovalNow(events.last.status);
  }
}

class _TaskList extends StatelessWidget {
  final List<DeviceTaskSummary> tasks;
  final String selectedTaskId;
  final bool loading;
  final ValueChanged<DeviceTaskSummary> onSelect;

  const _TaskList({
    required this.tasks,
    required this.selectedTaskId,
    required this.loading,
    required this.onSelect,
  });

  @override
  Widget build(BuildContext context) {
    return Material(
      color: Theme.of(context).colorScheme.surface,
      shape: RoundedRectangleBorder(
        side: BorderSide(color: Theme.of(context).dividerColor),
        borderRadius: BorderRadius.circular(8),
      ),
      child: tasks.isEmpty
          ? Center(child: Text(loading ? '加载中' : '暂无任务'))
          : ListView.separated(
              padding: const EdgeInsets.all(8),
              itemCount: tasks.length,
              separatorBuilder: (_, __) => const SizedBox(height: 6),
              itemBuilder: (context, index) {
                final task = tasks[index];
                return _TaskSummaryTile(
                  task: task,
                  selected: task.taskId == selectedTaskId,
                  onTap: () => onSelect(task),
                );
              },
            ),
    );
  }
}

class _TaskSummaryTile extends StatelessWidget {
  final DeviceTaskSummary task;
  final bool selected;
  final VoidCallback onTap;

  const _TaskSummaryTile({
    required this.task,
    required this.selected,
    required this.onTap,
  });

  @override
  Widget build(BuildContext context) {
    final scheme = Theme.of(context).colorScheme;
    final color = statusColor(task.status, task.level, scheme);
    final title = task.commandText.isEmpty ? task.message : task.commandText;
    return ListTile(
      selected: selected,
      selectedTileColor: scheme.primaryContainer.withValues(alpha: 0.6),
      leading: Icon(statusIcon(task.status), color: color),
      title: Text(
        title.isEmpty ? task.type : title,
        maxLines: 2,
        overflow: TextOverflow.ellipsis,
      ),
      subtitle: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          const SizedBox(height: 2),
          Text(
            '${task.status} · ${task.eventCount} 条事件',
            maxLines: 1,
            overflow: TextOverflow.ellipsis,
          ),
          Text(
            task.lastEventAt,
            maxLines: 1,
            overflow: TextOverflow.ellipsis,
            style: Theme.of(context).textTheme.bodySmall,
          ),
        ],
      ),
      trailing: task.recoverable ? const Icon(Icons.replay) : null,
      onTap: onTap,
      shape: RoundedRectangleBorder(borderRadius: BorderRadius.circular(8)),
    );
  }
}

class _TaskEventsPanel extends StatelessWidget {
  final TextEditingController controller;
  final List<DeviceTaskEvent> events;
  final String selectedTaskId;
  final bool loading;
  final bool scrollable;
  final bool deciding;
  final VoidCallback? onApprove;
  final VoidCallback? onReject;
  final VoidCallback onSearch;

  const _TaskEventsPanel({
    required this.controller,
    required this.events,
    required this.selectedTaskId,
    required this.loading,
    required this.scrollable,
    required this.deciding,
    required this.onApprove,
    required this.onReject,
    required this.onSearch,
  });

  @override
  Widget build(BuildContext context) {
    return Column(
      children: [
        TextField(
          controller: controller,
          decoration: InputDecoration(
            labelText: 'Task ID',
            suffixIcon: IconButton(
              icon: const Icon(Icons.search),
              onPressed: loading ? null : onSearch,
              tooltip: '查询事件',
            ),
            border: const OutlineInputBorder(),
          ),
          onSubmitted: (_) => onSearch(),
        ),
        const SizedBox(height: 12),
        if (onApprove != null || onReject != null) ...[
          _ApprovalActions(
            deciding: deciding,
            onApprove: onApprove,
            onReject: onReject,
          ),
          const SizedBox(height: 12),
        ],
        if (scrollable)
          Expanded(
              child: _EventList(events: events, selectedTaskId: selectedTaskId))
        else
          _EventList(
            events: events,
            selectedTaskId: selectedTaskId,
            shrinkWrap: true,
          ),
      ],
    );
  }
}

class _ApprovalActions extends StatelessWidget {
  final bool deciding;
  final VoidCallback? onApprove;
  final VoidCallback? onReject;

  const _ApprovalActions({
    required this.deciding,
    required this.onApprove,
    required this.onReject,
  });

  @override
  Widget build(BuildContext context) {
    return Align(
      alignment: Alignment.centerLeft,
      child: Wrap(
        spacing: 8,
        runSpacing: 8,
        children: [
          FilledButton.icon(
            onPressed: deciding ? null : onApprove,
            icon: deciding
                ? const SizedBox(
                    width: 16,
                    height: 16,
                    child: CircularProgressIndicator(strokeWidth: 2),
                  )
                : const Icon(Icons.check),
            label: const Text('批准执行'),
          ),
          OutlinedButton.icon(
            onPressed: deciding ? null : onReject,
            icon: const Icon(Icons.close),
            label: const Text('拒绝'),
          ),
        ],
      ),
    );
  }
}

class _EventList extends StatelessWidget {
  final List<DeviceTaskEvent> events;
  final String selectedTaskId;
  final bool shrinkWrap;

  const _EventList({
    required this.events,
    required this.selectedTaskId,
    this.shrinkWrap = false,
  });

  @override
  Widget build(BuildContext context) {
    if (events.isEmpty) {
      return SizedBox(
        height: shrinkWrap ? 160 : null,
        child: Center(child: Text(selectedTaskId.isEmpty ? '暂无任务' : '暂无事件')),
      );
    }
    return ListView.separated(
      shrinkWrap: shrinkWrap,
      physics: shrinkWrap ? const NeverScrollableScrollPhysics() : null,
      itemCount: events.length,
      separatorBuilder: (_, __) => const SizedBox(height: 8),
      itemBuilder: (context, index) => _EventTile(event: events[index]),
    );
  }
}

class _EventTile extends StatelessWidget {
  final DeviceTaskEvent event;

  const _EventTile({required this.event});

  @override
  Widget build(BuildContext context) {
    final scheme = Theme.of(context).colorScheme;
    final color = statusColor(event.status, event.level, scheme);
    return ListTile(
      leading: Icon(statusIcon(event.status), color: color),
      title: Text('${event.type} · ${event.status}'),
      subtitle: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          Text(event.message.isEmpty ? event.createdAt : event.message),
          if (event.output.isNotEmpty) SelectableText(event.output),
        ],
      ),
      trailing: event.recoverable ? const Icon(Icons.replay) : null,
      shape: RoundedRectangleBorder(
        side: BorderSide(color: Theme.of(context).dividerColor),
        borderRadius: BorderRadius.circular(8),
      ),
    );
  }
}

Color statusColor(String status, String level, ColorScheme scheme) {
  if (level.toLowerCase() == 'error' || status == DeviceTaskStatus.failed) {
    return scheme.error;
  }
  if (level.toLowerCase() == 'warn' ||
      status == DeviceTaskStatus.needsApproval) {
    return Colors.orange;
  }
  if (status == DeviceTaskStatus.completed) return Colors.green;
  if (status == DeviceTaskStatus.running) return scheme.primary;
  return scheme.outline;
}

IconData statusIcon(String status) {
  return switch (status) {
    DeviceTaskStatus.completed => Icons.check_circle,
    DeviceTaskStatus.failed => Icons.error,
    DeviceTaskStatus.rejected => Icons.block,
    DeviceTaskStatus.running => Icons.play_circle,
    DeviceTaskStatus.needsApproval => Icons.rule,
    DeviceTaskStatus.queued => Icons.schedule,
    _ => Icons.circle,
  };
}
