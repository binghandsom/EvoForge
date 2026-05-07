import 'dart:convert';
import 'dart:async';

import 'package:flutter/material.dart';

import '../../shared/api/evoforge_api.dart';
import '../../shared/models/device_task_event.dart';
import '../../shared/models/device_protocol.dart';
import '../../shared/widgets/page_header.dart';

class CommandCenterPage extends StatefulWidget {
  final EvoForgeApi api;

  const CommandCenterPage({super.key, required this.api});

  @override
  State<CommandCenterPage> createState() => _CommandCenterPageState();
}

class _CommandCenterPageState extends State<CommandCenterPage> {
  final TextEditingController inputController = TextEditingController();
  List<DeviceTaskEvent> events = [];
  String taskId = '';
  String commandType = DeviceCommandType.naturalLanguageTask;
  bool sending = false;
  bool approving = false;
  bool requiresApproval = false;
  Timer? pollTimer;

  @override
  void dispose() {
    stopPolling();
    inputController.dispose();
    super.dispose();
  }

  Future<void> send() async {
    final text = inputController.text.trim();
    if (text.isEmpty) {
      showMessage('请输入任务');
      return;
    }
    setState(() {
      sending = true;
      taskId = '';
      events = [];
    });
    try {
      final queued = await widget.api.dispatchDeviceCommand(
        text: text,
        type: commandType,
        requiresApproval: requiresApproval,
      );
      setState(() {
        taskId = queued.taskId;
        events = [queued];
      });
      await refreshEvents();
      startPolling();
    } catch (e) {
      showMessage('发送失败: $e');
    } finally {
      if (mounted) setState(() => sending = false);
    }
  }

  Future<void> refreshEvents() async {
    if (taskId.isEmpty) return;
    try {
      final data = await widget.api.loadTaskEvents(taskId);
      if (!mounted) return;
      setState(() => events = data);
      if (data.isNotEmpty && isTerminalStatus(data.last.status)) {
        stopPolling();
      }
    } catch (e) {
      showMessage('刷新事件失败: $e');
    }
  }

  void startPolling() {
    stopPolling();
    if (taskId.isEmpty || latestIsTerminal) return;
    pollTimer = Timer.periodic(
      const Duration(seconds: 2),
      (_) => refreshEvents(),
    );
  }

  void stopPolling() {
    pollTimer?.cancel();
    pollTimer = null;
  }

  bool get latestIsTerminal {
    if (events.isEmpty) return false;
    return isTerminalStatus(events.last.status);
  }

  bool get latestNeedsApproval {
    return events.isNotEmpty &&
        DeviceTaskStatus.needsApprovalNow(events.last.status);
  }

  Future<void> approveLatestTask() async {
    await decideLatestTask(approve: true);
  }

  Future<void> rejectLatestTask() async {
    await decideLatestTask(approve: false);
  }

  Future<void> decideLatestTask({required bool approve}) async {
    if (taskId.isEmpty || approving) return;
    setState(() => approving = true);
    try {
      if (approve) {
        await widget.api.approveTask(taskId);
      } else {
        await widget.api.rejectTask(taskId);
      }
      await refreshEvents();
      if (approve) startPolling();
    } catch (e) {
      showMessage('${approve ? '批准' : '拒绝'}失败: $e');
    } finally {
      if (mounted) setState(() => approving = false);
    }
  }

  void showMessage(String message) {
    if (!mounted) return;
    ScaffoldMessenger.of(context)
        .showSnackBar(SnackBar(content: Text(message)));
  }

  @override
  Widget build(BuildContext context) {
    return Column(
      children: [
        PageHeader(
          title: '指挥台',
          subtitle: '投递任务并查看执行事件',
          actions: [
            FilledButton.icon(
              onPressed: sending ? null : send,
              icon: sending
                  ? const SizedBox(
                      width: 16,
                      height: 16,
                      child: CircularProgressIndicator(strokeWidth: 2))
                  : const Icon(Icons.send),
              label: const Text('发送'),
            ),
            const SizedBox(width: 8),
            IconButton(
              onPressed: taskId.isEmpty
                  ? null
                  : () async {
                      await refreshEvents();
                      startPolling();
                    },
              icon: const Icon(Icons.refresh),
              tooltip: '刷新事件',
            ),
          ],
        ),
        Expanded(
          child: ListView(
            padding: const EdgeInsets.fromLTRB(24, 4, 24, 24),
            children: [
              TextField(
                controller: inputController,
                onChanged: (_) => setState(() {}),
                minLines: 4,
                maxLines: 8,
                decoration: const InputDecoration(
                    labelText: '任务', border: OutlineInputBorder()),
              ),
              const SizedBox(height: 12),
              Wrap(
                spacing: 12,
                runSpacing: 8,
                crossAxisAlignment: WrapCrossAlignment.center,
                children: [
                  SegmentedButton<String>(
                    segments: const [
                      ButtonSegment(
                        value: DeviceCommandType.naturalLanguageTask,
                        icon: Icon(Icons.chat),
                        label: Text('普通任务'),
                      ),
                      ButtonSegment(
                        value: DeviceCommandType.codexTask,
                        icon: Icon(Icons.code),
                        label: Text('Codex'),
                      ),
                    ],
                    selected: {commandType},
                    onSelectionChanged: (value) =>
                        setState(() => commandType = value.first),
                  ),
                  FilterChip(
                    avatar: const Icon(Icons.rule, size: 18),
                    label: const Text('需要确认'),
                    selected: requiresApproval,
                    onSelected: (value) =>
                        setState(() => requiresApproval = value),
                  ),
                ],
              ),
              const SizedBox(height: 16),
              _CommandPreview(
                text: inputController.text,
                type: commandType,
                requiresApproval: requiresApproval,
              ),
              const SizedBox(height: 16),
              _TimelinePanel(
                taskId: taskId,
                events: events,
                approving: approving,
                onApprove: latestNeedsApproval ? approveLatestTask : null,
                onReject: latestNeedsApproval ? rejectLatestTask : null,
              ),
            ],
          ),
        ),
      ],
    );
  }
}

bool isTerminalStatus(String status) {
  return switch (status) {
    _ => DeviceTaskStatus.isTerminal(status),
  };
}

class _CommandPreview extends StatelessWidget {
  final String text;
  final String type;
  final bool requiresApproval;

  const _CommandPreview({
    required this.text,
    required this.type,
    required this.requiresApproval,
  });

  @override
  Widget build(BuildContext context) {
    final payload = {
      'type': type,
      'text': text.trim().isEmpty ? '...' : text.trim(),
      'requiresApproval': requiresApproval,
      'attributes': {},
    };

    return Container(
      width: double.infinity,
      padding: const EdgeInsets.all(14),
      decoration: BoxDecoration(
        border: Border.all(color: Theme.of(context).dividerColor),
        borderRadius: BorderRadius.circular(8),
      ),
      child:
          SelectableText(const JsonEncoder.withIndent('  ').convert(payload)),
    );
  }
}

class _TimelinePanel extends StatelessWidget {
  final String taskId;
  final List<DeviceTaskEvent> events;
  final bool approving;
  final VoidCallback? onApprove;
  final VoidCallback? onReject;

  const _TimelinePanel({
    required this.taskId,
    required this.events,
    required this.approving,
    required this.onApprove,
    required this.onReject,
  });

  @override
  Widget build(BuildContext context) {
    return Column(
      crossAxisAlignment: CrossAxisAlignment.start,
      children: [
        Row(
          children: [
            Text('事件流', style: Theme.of(context).textTheme.titleMedium),
            const SizedBox(width: 12),
            if (taskId.isNotEmpty) Expanded(child: SelectableText(taskId)),
          ],
        ),
        const SizedBox(height: 8),
        if (onApprove != null || onReject != null) ...[
          _ApprovalBar(
            approving: approving,
            onApprove: onApprove,
            onReject: onReject,
          ),
          const SizedBox(height: 8),
        ],
        if (events.isEmpty)
          Container(
            width: double.infinity,
            constraints: const BoxConstraints(minHeight: 160),
            padding: const EdgeInsets.all(14),
            decoration: BoxDecoration(
              color: Colors.grey.shade50,
              border: Border.all(color: Theme.of(context).dividerColor),
              borderRadius: BorderRadius.circular(8),
            ),
            child: const Text('暂无事件'),
          )
        else
          ...events.map((event) => _EventTile(event: event)),
      ],
    );
  }
}

class _ApprovalBar extends StatelessWidget {
  final bool approving;
  final VoidCallback? onApprove;
  final VoidCallback? onReject;

  const _ApprovalBar({
    required this.approving,
    required this.onApprove,
    required this.onReject,
  });

  @override
  Widget build(BuildContext context) {
    return Row(
      children: [
        FilledButton.icon(
          onPressed: approving ? null : onApprove,
          icon: approving
              ? const SizedBox(
                  width: 16,
                  height: 16,
                  child: CircularProgressIndicator(strokeWidth: 2),
                )
              : const Icon(Icons.check),
          label: const Text('批准执行'),
        ),
        const SizedBox(width: 8),
        OutlinedButton.icon(
          onPressed: approving ? null : onReject,
          icon: const Icon(Icons.close),
          label: const Text('拒绝'),
        ),
      ],
    );
  }
}

class _EventTile extends StatelessWidget {
  final DeviceTaskEvent event;

  const _EventTile({required this.event});

  @override
  Widget build(BuildContext context) {
    final color = switch (event.level.toLowerCase()) {
      'error' => Colors.red,
      'warn' => Colors.orange,
      _ => Colors.blueGrey,
    };
    return Padding(
      padding: const EdgeInsets.only(bottom: 8),
      child: ListTile(
        leading: Icon(Icons.circle, size: 14, color: color),
        title: Text('${event.type} · ${event.status}'),
        subtitle: Column(
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            Text(event.message.isEmpty ? event.createdAt : event.message),
            if (event.output.isNotEmpty)
              SelectableText(const JsonEncoder.withIndent('  ')
                  .convert({'output': event.output})),
            if (event.payload.isNotEmpty)
              SelectableText(
                const JsonEncoder.withIndent('  ').convert(event.payload),
              ),
          ],
        ),
        shape: RoundedRectangleBorder(
          side: BorderSide(color: Theme.of(context).dividerColor),
          borderRadius: BorderRadius.circular(8),
        ),
      ),
    );
  }
}
