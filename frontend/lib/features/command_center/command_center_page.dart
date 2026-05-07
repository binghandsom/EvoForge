import 'dart:async';
import 'dart:convert';

import 'package:flutter/material.dart';

import '../../shared/api/evoforge_api.dart';
import '../../shared/models/agent_conversation.dart';
import '../../shared/models/codex_task_status.dart';
import '../../shared/models/device_task_event.dart';
import '../../shared/models/device_protocol.dart';
import '../../shared/security/device_command_signer.dart';
import '../../shared/widgets/page_header.dart';

class CommandCenterPage extends StatefulWidget {
  final EvoForgeApi api;

  const CommandCenterPage({super.key, required this.api});

  @override
  State<CommandCenterPage> createState() => _CommandCenterPageState();
}

class _CommandCenterPageState extends State<CommandCenterPage> {
  final TextEditingController inputController = TextEditingController();
  List<AgentConversationThread> conversations = [];
  List<AgentConversationTurn> turns = [];
  List<DeviceTaskEvent> events = [];
  List<CodexWorkspaceStatus> codexWorkspaces = [];
  String taskId = '';
  String threadId = _newThreadId();
  String selectedProjectKey = '';
  String commandType = DeviceCommandType.naturalLanguageTask;
  bool sending = false;
  bool approving = false;
  bool loadingConversations = false;
  bool loadingTurns = false;
  bool loadingDeviceStatus = false;
  bool requiresApproval = false;
  bool evoforgeLearning = false;
  Timer? pollTimer;
  StreamSubscription<DeviceTaskEvent>? messageBusSubscription;

  @override
  void initState() {
    super.initState();
    subscribeMessageBusEvents();
    loadConversationWorkspace();
    loadDeviceStatus();
  }

  @override
  void didUpdateWidget(covariant CommandCenterPage oldWidget) {
    super.didUpdateWidget(oldWidget);
    if (oldWidget.api == widget.api) return;
    subscribeMessageBusEvents();
    loadDeviceStatus();
  }

  @override
  void dispose() {
    stopPolling();
    messageBusSubscription?.cancel();
    inputController.dispose();
    super.dispose();
  }

  void subscribeMessageBusEvents() {
    unawaited(messageBusSubscription?.cancel());
    messageBusSubscription = widget.api.messageBusEvents.listen(
      receiveMessageBusEvent,
    );
  }

  Future<void> send() async {
    final text = inputController.text.trim();
    if (text.isEmpty) {
      showMessage('请输入任务');
      return;
    }
    if (commandType == DeviceCommandType.codexTask &&
        codexWorkspaces.isNotEmpty &&
        selectedProjectKey.isEmpty) {
      showMessage('请选择 Codex 执行项目');
      return;
    }
    setState(() {
      sending = true;
      taskId = '';
      events = [];
    });
    try {
      await ensureCurrentConversationExists();
      final queued = await widget.api.dispatchDeviceCommand(
        text: text,
        type: commandType,
        requiresApproval: requiresApproval,
        attributes: commandAttributes(),
      );
      setState(() {
        taskId = queued.taskId;
        events = _sortedEvents([queued]);
      });
      if (widget.api.usesMessageBus) {
        showMessage('任务已发送，正在等待事件流返回');
      } else {
        await refreshEvents();
        await loadConversations(keepSelection: true);
        startPolling();
      }
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
      final ordered = _sortedEvents(data);
      setState(() => events = ordered);
      if (ordered.isNotEmpty && isTerminalStatus(ordered.last.status)) {
        stopPolling();
        await loadConversationTurns(threadId);
        await loadConversations(keepSelection: true);
      }
    } catch (e) {
      showMessage('刷新事件失败: $e');
    }
  }

  void startPolling() {
    stopPolling();
    if (widget.api.usesMessageBus) return;
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

  void receiveMessageBusEvent(DeviceTaskEvent event) {
    if (!mounted || taskId.isEmpty || event.taskId != taskId) return;
    final wasTerminal = latestIsTerminal;
    setState(() {
      final index = events.indexWhere((item) => item.eventId == event.eventId);
      final nextEvents = [...events];
      if (index >= 0) {
        nextEvents[index] = event;
      } else {
        nextEvents.add(event);
      }
      events = _sortedEvents(nextEvents);
    });
    if (!wasTerminal && isTerminalStatus(event.status)) {
      stopPolling();
      loadConversationTurns(threadId);
      loadConversations(keepSelection: true);
    }
  }

  Future<void> loadConversationWorkspace() async {
    await loadConversations();
    if (threadId.isNotEmpty) {
      await loadConversationTurns(threadId);
    }
  }

  Future<void> loadDeviceStatus() async {
    if (loadingDeviceStatus) return;
    setState(() => loadingDeviceStatus = true);
    try {
      final status = await widget.api.loadDeviceStatus();
      if (!mounted) return;
      final workspaces = status.codexTask.workspaces
          .where((workspace) => workspace.key.isNotEmpty)
          .toList();
      final defaultKey = status.codexTask.defaultWorkspace;
      final currentStillExists =
          workspaces.any((workspace) => workspace.key == selectedProjectKey);
      var nextProjectKey = selectedProjectKey;
      if (!currentStillExists) {
        nextProjectKey =
            workspaces.map((workspace) => workspace.key).firstWhere(
                  (key) => key == defaultKey,
                  orElse: () => workspaces.isEmpty ? '' : workspaces.first.key,
                );
      }
      setState(() {
        codexWorkspaces = workspaces;
        selectedProjectKey = nextProjectKey;
      });
    } catch (e) {
      showMessage('项目配置加载失败: $e');
    } finally {
      if (mounted) setState(() => loadingDeviceStatus = false);
    }
  }

  Future<void> loadConversations({bool keepSelection = false}) async {
    if (loadingConversations) return;
    setState(() => loadingConversations = true);
    try {
      final data = await widget.api.loadConversations();
      if (!mounted) return;
      String nextThreadId = threadId;
      final hasCurrent = data.any((item) => item.threadId == threadId);
      if (!keepSelection && data.isNotEmpty && !hasCurrent) {
        nextThreadId = data.first.threadId;
      }
      setState(() {
        conversations = data;
        threadId = nextThreadId;
      });
    } catch (e) {
      showMessage('对话加载失败: $e');
    } finally {
      if (mounted) setState(() => loadingConversations = false);
    }
  }

  Future<void> loadConversationTurns(String targetThreadId) async {
    if (targetThreadId.isEmpty) return;
    setState(() => loadingTurns = true);
    try {
      final data = await widget.api.loadConversationTurns(targetThreadId);
      if (mounted) setState(() => turns = data);
    } catch (e) {
      showMessage('上下文加载失败: $e');
    } finally {
      if (mounted) setState(() => loadingTurns = false);
    }
  }

  Future<void> ensureCurrentConversationExists() async {
    if (conversations.any((item) => item.threadId == threadId)) return;
    final created = await widget.api.createConversation(threadId: threadId);
    if (!mounted) return;
    setState(() => conversations = [created, ...conversations]);
  }

  Future<void> selectConversation(AgentConversationThread thread) async {
    stopPolling();
    setState(() {
      threadId = thread.threadId;
      taskId = '';
      events = [];
      inputController.clear();
    });
    await loadConversationTurns(thread.threadId);
  }

  void showMessage(String message) {
    if (!mounted) return;
    ScaffoldMessenger.of(context)
        .showSnackBar(SnackBar(content: Text(message)));
  }

  Future<void> startNewThread() async {
    stopPolling();
    final nextThreadId = _newThreadId();
    setState(() {
      threadId = nextThreadId;
      taskId = '';
      events = [];
      turns = [];
      inputController.clear();
    });
    try {
      final created = await widget.api.createConversation(
        threadId: nextThreadId,
      );
      if (!mounted) return;
      setState(() => conversations = [created, ...conversations]);
    } catch (e) {
      showMessage('新建对话失败: $e');
    }
  }

  Map<String, Object?> commandAttributes() {
    final attributes = <String, Object?>{
      'threadId': threadId,
      'conversationMode': 'thread-memory',
    };
    if (commandType == DeviceCommandType.codexTask) {
      if (selectedProjectKey.isNotEmpty) {
        attributes['projectKey'] = selectedProjectKey;
      }
      if (evoforgeLearning) {
        attributes['evoforgeLearning'] = {
          'enabled': true,
          'targetProjectKey': 'evoforge',
          'sourceProjectKey': selectedProjectKey,
          'recordChangeLineage': true,
          'learningScopes': [
            'task-intent',
            'change-lineage',
            'codex-output',
            'errors',
            'skills',
            'project-facts',
          ],
        };
      }
    }
    return attributes;
  }

  @override
  Widget build(BuildContext context) {
    return Column(
      children: [
        PageHeader(
          title: '指挥台',
          subtitle: '编排任务、观察模型计划与工具执行',
          actions: [
            OutlinedButton.icon(
              onPressed: sending
                  ? null
                  : () {
                      startNewThread();
                    },
              icon: const Icon(Icons.add_comment),
              label: const Text('新对话'),
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
        if (loadingConversations || loadingTurns || loadingDeviceStatus)
          const LinearProgressIndicator(),
        Expanded(
          child: LayoutBuilder(
            builder: (context, constraints) {
              final narrow = constraints.maxWidth < 900;
              final workspace = _CommandWorkspace(
                inputController: inputController,
                commandType: commandType,
                requiresApproval: requiresApproval,
                threadId: threadId,
                turns: turns,
                taskId: taskId,
                events: events,
                codexWorkspaces: codexWorkspaces,
                selectedProjectKey: selectedProjectKey,
                evoforgeLearning: evoforgeLearning,
                sending: sending,
                approving: approving,
                onInputChanged: () => setState(() {}),
                onCommandTypeChanged: (value) =>
                    setState(() => commandType = value),
                onRequiresApprovalChanged: (value) =>
                    setState(() => requiresApproval = value),
                onProjectKeyChanged: (value) =>
                    setState(() => selectedProjectKey = value),
                onEvoforgeLearningChanged: (value) =>
                    setState(() => evoforgeLearning = value),
                onSend: send,
                onApprove: latestNeedsApproval ? approveLatestTask : null,
                onReject: latestNeedsApproval ? rejectLatestTask : null,
              );
              final rail = _ConversationList(
                conversations: conversations,
                selectedThreadId: threadId,
                loading: loadingConversations,
                onRefresh: () => loadConversations(keepSelection: true),
                onNew: startNewThread,
                onSelect: selectConversation,
              );

              if (narrow) {
                return ListView(
                  padding: const EdgeInsets.fromLTRB(16, 4, 16, 16),
                  children: [
                    SizedBox(height: 260, child: rail),
                    const SizedBox(height: 12),
                    workspace,
                  ],
                );
              }
              return Row(
                children: [
                  SizedBox(
                    width: 320,
                    child: Padding(
                      padding: const EdgeInsets.fromLTRB(24, 4, 12, 24),
                      child: rail,
                    ),
                  ),
                  const VerticalDivider(width: 1),
                  Expanded(
                    child: ListView(
                      padding: const EdgeInsets.fromLTRB(16, 4, 24, 24),
                      children: [workspace],
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

String _newThreadId() => 'thread-${DeviceCommandSigner.uuidV4()}';

String _shortId(String value) {
  if (value.length <= 8) return value;
  return value.substring(value.length - 8);
}

String _commandTypeLabel(String type) {
  return switch (type) {
    DeviceCommandType.codexTask => 'Codex',
    DeviceCommandType.naturalLanguageTask => '普通任务',
    DeviceCommandType.approvalDecision => '确认决策',
    DeviceCommandType.clientRequest => '客户端请求',
    _ => type,
  };
}

String _statusLabel(String status) {
  return switch (status) {
    DeviceTaskStatus.queued => '排队中',
    DeviceTaskStatus.accepted => '已接收',
    DeviceTaskStatus.running => '执行中',
    DeviceTaskStatus.completed => '已完成',
    DeviceTaskStatus.failed => '失败',
    DeviceTaskStatus.needsApproval => '等待确认',
    DeviceTaskStatus.approved => '已批准',
    DeviceTaskStatus.rejected => '已拒绝',
    DeviceTaskStatus.clientResponse => '客户端响应',
    DeviceTaskStatus.agentProgress => '模型进展',
    'idle' => '未开始',
    _ => status,
  };
}

String _turnRoleLabel(String role) {
  return switch (role) {
    'user' => '用户',
    'assistant' => '助手',
    'system' => '系统',
    _ => role.isEmpty ? '消息' : role,
  };
}

bool isTerminalStatus(String status) {
  return switch (status) {
    _ => DeviceTaskStatus.isTerminal(status),
  };
}

List<DeviceTaskEvent> _sortedEvents(Iterable<DeviceTaskEvent> events) {
  final sorted = events.toList();
  sorted.sort((a, b) {
    final byTime = a.createdAt.compareTo(b.createdAt);
    if (byTime != 0) return byTime;
    return a.eventId.compareTo(b.eventId);
  });
  return sorted;
}

class _ConversationList extends StatelessWidget {
  final List<AgentConversationThread> conversations;
  final String selectedThreadId;
  final bool loading;
  final Future<void> Function() onRefresh;
  final Future<void> Function() onNew;
  final Future<void> Function(AgentConversationThread thread) onSelect;

  const _ConversationList({
    required this.conversations,
    required this.selectedThreadId,
    required this.loading,
    required this.onRefresh,
    required this.onNew,
    required this.onSelect,
  });

  @override
  Widget build(BuildContext context) {
    return Container(
      decoration: BoxDecoration(
        border: Border.all(color: Theme.of(context).dividerColor),
        borderRadius: BorderRadius.circular(8),
      ),
      child: Column(
        children: [
          Padding(
            padding: const EdgeInsets.fromLTRB(12, 8, 8, 8),
            child: Row(
              children: [
                Expanded(
                  child: Text(
                    '对话',
                    style: Theme.of(context).textTheme.titleMedium,
                  ),
                ),
                IconButton(
                  onPressed: loading
                      ? null
                      : () {
                          onRefresh();
                        },
                  icon: const Icon(Icons.refresh),
                  tooltip: '刷新对话',
                ),
                IconButton(
                  onPressed: loading
                      ? null
                      : () {
                          onNew();
                        },
                  icon: const Icon(Icons.add_comment),
                  tooltip: '新对话',
                ),
              ],
            ),
          ),
          const Divider(height: 1),
          Expanded(
            child: conversations.isEmpty
                ? Center(
                    child: Text(
                      loading ? '加载中' : '暂无对话',
                      style: Theme.of(context).textTheme.bodyMedium,
                    ),
                  )
                : ListView.separated(
                    itemCount: conversations.length,
                    separatorBuilder: (_, __) => const Divider(height: 1),
                    itemBuilder: (context, index) {
                      final item = conversations[index];
                      final selected = item.threadId == selectedThreadId;
                      return ListTile(
                        selected: selected,
                        leading: Icon(
                          selected ? Icons.forum : Icons.chat_bubble_outline,
                        ),
                        title: Text(
                          item.title.isEmpty ? '新对话' : item.title,
                          maxLines: 1,
                          overflow: TextOverflow.ellipsis,
                        ),
                        subtitle: Text(
                          item.lastContent.isEmpty ? '未开始' : item.lastContent,
                          maxLines: 2,
                          overflow: TextOverflow.ellipsis,
                        ),
                        trailing: Text('${item.turnCount}'),
                        onTap: () {
                          onSelect(item);
                        },
                      );
                    },
                  ),
          ),
        ],
      ),
    );
  }
}

class _CommandWorkspace extends StatelessWidget {
  final TextEditingController inputController;
  final String commandType;
  final bool requiresApproval;
  final String threadId;
  final List<AgentConversationTurn> turns;
  final String taskId;
  final List<DeviceTaskEvent> events;
  final List<CodexWorkspaceStatus> codexWorkspaces;
  final String selectedProjectKey;
  final bool evoforgeLearning;
  final bool sending;
  final bool approving;
  final VoidCallback onInputChanged;
  final ValueChanged<String> onCommandTypeChanged;
  final ValueChanged<bool> onRequiresApprovalChanged;
  final ValueChanged<String> onProjectKeyChanged;
  final ValueChanged<bool> onEvoforgeLearningChanged;
  final VoidCallback onSend;
  final VoidCallback? onApprove;
  final VoidCallback? onReject;

  const _CommandWorkspace({
    required this.inputController,
    required this.commandType,
    required this.requiresApproval,
    required this.threadId,
    required this.turns,
    required this.taskId,
    required this.events,
    required this.codexWorkspaces,
    required this.selectedProjectKey,
    required this.evoforgeLearning,
    required this.sending,
    required this.approving,
    required this.onInputChanged,
    required this.onCommandTypeChanged,
    required this.onRequiresApprovalChanged,
    required this.onProjectKeyChanged,
    required this.onEvoforgeLearningChanged,
    required this.onSend,
    required this.onApprove,
    required this.onReject,
  });

  @override
  Widget build(BuildContext context) {
    return LayoutBuilder(
      builder: (context, constraints) {
        final useInspector = constraints.maxWidth >= 980;
        final main = _RunWorkspace(
          inputController: inputController,
          commandType: commandType,
          requiresApproval: requiresApproval,
          threadId: threadId,
          taskId: taskId,
          events: events,
          codexWorkspaces: codexWorkspaces,
          selectedProjectKey: selectedProjectKey,
          evoforgeLearning: evoforgeLearning,
          sending: sending,
          approving: approving,
          onInputChanged: onInputChanged,
          onCommandTypeChanged: onCommandTypeChanged,
          onRequiresApprovalChanged: onRequiresApprovalChanged,
          onProjectKeyChanged: onProjectKeyChanged,
          onEvoforgeLearningChanged: onEvoforgeLearningChanged,
          onSend: onSend,
          onApprove: onApprove,
          onReject: onReject,
        );
        final inspector = _CommandInspector(
          turns: turns,
          text: inputController.text,
          type: commandType,
          requiresApproval: requiresApproval,
          selectedProjectKey: selectedProjectKey,
          evoforgeLearning: evoforgeLearning,
          threadId: threadId,
          taskId: taskId,
          events: events,
        );

        if (!useInspector) {
          return Column(
            crossAxisAlignment: CrossAxisAlignment.start,
            children: [
              main,
              const SizedBox(height: 16),
              inspector,
            ],
          );
        }
        return Row(
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            Expanded(child: main),
            const SizedBox(width: 16),
            SizedBox(width: 340, child: inspector),
          ],
        );
      },
    );
  }
}

class _RunWorkspace extends StatelessWidget {
  final TextEditingController inputController;
  final String commandType;
  final bool requiresApproval;
  final String threadId;
  final String taskId;
  final List<DeviceTaskEvent> events;
  final List<CodexWorkspaceStatus> codexWorkspaces;
  final String selectedProjectKey;
  final bool evoforgeLearning;
  final bool sending;
  final bool approving;
  final VoidCallback onInputChanged;
  final ValueChanged<String> onCommandTypeChanged;
  final ValueChanged<bool> onRequiresApprovalChanged;
  final ValueChanged<String> onProjectKeyChanged;
  final ValueChanged<bool> onEvoforgeLearningChanged;
  final VoidCallback onSend;
  final VoidCallback? onApprove;
  final VoidCallback? onReject;

  const _RunWorkspace({
    required this.inputController,
    required this.commandType,
    required this.requiresApproval,
    required this.threadId,
    required this.taskId,
    required this.events,
    required this.codexWorkspaces,
    required this.selectedProjectKey,
    required this.evoforgeLearning,
    required this.sending,
    required this.approving,
    required this.onInputChanged,
    required this.onCommandTypeChanged,
    required this.onRequiresApprovalChanged,
    required this.onProjectKeyChanged,
    required this.onEvoforgeLearningChanged,
    required this.onSend,
    required this.onApprove,
    required this.onReject,
  });

  @override
  Widget build(BuildContext context) {
    return Column(
      crossAxisAlignment: CrossAxisAlignment.start,
      children: [
        _CommandComposer(
          inputController: inputController,
          commandType: commandType,
          requiresApproval: requiresApproval,
          threadId: threadId,
          codexWorkspaces: codexWorkspaces,
          selectedProjectKey: selectedProjectKey,
          evoforgeLearning: evoforgeLearning,
          sending: sending,
          onInputChanged: onInputChanged,
          onCommandTypeChanged: onCommandTypeChanged,
          onRequiresApprovalChanged: onRequiresApprovalChanged,
          onProjectKeyChanged: onProjectKeyChanged,
          onEvoforgeLearningChanged: onEvoforgeLearningChanged,
          onSend: onSend,
        ),
        const SizedBox(height: 12),
        _RunStatusStrip(
          taskId: taskId,
          commandType: commandType,
          requiresApproval: requiresApproval,
          selectedProjectKey: selectedProjectKey,
          evoforgeLearning: evoforgeLearning,
          events: events,
        ),
        const SizedBox(height: 12),
        _TimelinePanel(
          taskId: taskId,
          events: events,
          approving: approving,
          onApprove: onApprove,
          onReject: onReject,
        ),
      ],
    );
  }
}

class _CommandComposer extends StatelessWidget {
  final TextEditingController inputController;
  final String commandType;
  final bool requiresApproval;
  final String threadId;
  final List<CodexWorkspaceStatus> codexWorkspaces;
  final String selectedProjectKey;
  final bool evoforgeLearning;
  final bool sending;
  final VoidCallback onInputChanged;
  final ValueChanged<String> onCommandTypeChanged;
  final ValueChanged<bool> onRequiresApprovalChanged;
  final ValueChanged<String> onProjectKeyChanged;
  final ValueChanged<bool> onEvoforgeLearningChanged;
  final VoidCallback onSend;

  const _CommandComposer({
    required this.inputController,
    required this.commandType,
    required this.requiresApproval,
    required this.threadId,
    required this.codexWorkspaces,
    required this.selectedProjectKey,
    required this.evoforgeLearning,
    required this.sending,
    required this.onInputChanged,
    required this.onCommandTypeChanged,
    required this.onRequiresApprovalChanged,
    required this.onProjectKeyChanged,
    required this.onEvoforgeLearningChanged,
    required this.onSend,
  });

  @override
  Widget build(BuildContext context) {
    return Container(
      width: double.infinity,
      padding: const EdgeInsets.all(14),
      decoration: BoxDecoration(
        color: Theme.of(context).colorScheme.surface,
        border: Border.all(color: Theme.of(context).dividerColor),
        borderRadius: BorderRadius.circular(8),
      ),
      child: Column(
        children: [
          TextField(
            controller: inputController,
            onChanged: (_) => onInputChanged(),
            minLines: 3,
            maxLines: 7,
            decoration: const InputDecoration(
              labelText: '任务指令',
              border: OutlineInputBorder(),
              alignLabelWithHint: true,
            ),
          ),
          const SizedBox(height: 12),
          LayoutBuilder(
            builder: (context, constraints) {
              final controls = _buildControls();
              final sendButton = _buildSendButton();
              if (constraints.maxWidth < 620) {
                return Column(
                  crossAxisAlignment: CrossAxisAlignment.start,
                  children: [
                    controls,
                    const SizedBox(height: 10),
                    Align(
                      alignment: Alignment.centerRight,
                      child: sendButton,
                    ),
                  ],
                );
              }
              return Row(
                crossAxisAlignment: CrossAxisAlignment.center,
                children: [
                  Expanded(child: controls),
                  const SizedBox(width: 12),
                  sendButton,
                ],
              );
            },
          ),
        ],
      ),
    );
  }

  Widget _buildControls() {
    return Wrap(
      spacing: 10,
      runSpacing: 8,
      crossAxisAlignment: WrapCrossAlignment.center,
      children: [
        SegmentedButton<String>(
          segments: const [
            ButtonSegment(
              value: DeviceCommandType.naturalLanguageTask,
              icon: Icon(Icons.chat),
              label: Text('任务'),
            ),
            ButtonSegment(
              value: DeviceCommandType.codexTask,
              icon: Icon(Icons.code),
              label: Text('Codex'),
            ),
          ],
          selected: {commandType},
          onSelectionChanged: (value) => onCommandTypeChanged(value.first),
        ),
        FilterChip(
          avatar: const Icon(Icons.rule, size: 18),
          label: const Text('确认后执行'),
          selected: requiresApproval,
          onSelected: onRequiresApprovalChanged,
        ),
        if (commandType == DeviceCommandType.codexTask)
          _ProjectSelector(
            workspaces: codexWorkspaces,
            selectedProjectKey: selectedProjectKey,
            onChanged: onProjectKeyChanged,
          ),
        if (commandType == DeviceCommandType.codexTask)
          FilterChip(
            avatar: const Icon(Icons.school, size: 18),
            label: const Text('EvoForge 学习'),
            selected: evoforgeLearning,
            onSelected: onEvoforgeLearningChanged,
          ),
        Tooltip(
          message: threadId,
          child: Chip(
            avatar: const Icon(Icons.forum, size: 18),
            label: Text(_shortId(threadId)),
          ),
        ),
      ],
    );
  }

  Widget _buildSendButton() {
    return FilledButton.icon(
      onPressed: sending ? null : onSend,
      icon: sending
          ? const SizedBox(
              width: 16,
              height: 16,
              child: CircularProgressIndicator(strokeWidth: 2),
            )
          : const Icon(Icons.send),
      label: const Text('发送'),
    );
  }
}

class _ProjectSelector extends StatelessWidget {
  final List<CodexWorkspaceStatus> workspaces;
  final String selectedProjectKey;
  final ValueChanged<String> onChanged;

  const _ProjectSelector({
    required this.workspaces,
    required this.selectedProjectKey,
    required this.onChanged,
  });

  @override
  Widget build(BuildContext context) {
    if (workspaces.isEmpty) {
      return const Tooltip(
        message: '尚未加载到 codexTask.workspaces，后端会使用默认配置',
        child: Chip(
          avatar: Icon(Icons.folder_off, size: 18),
          label: Text('默认项目'),
        ),
      );
    }
    final selected = workspaces.any((item) => item.key == selectedProjectKey)
        ? selectedProjectKey
        : workspaces.first.key;
    return ConstrainedBox(
      constraints: const BoxConstraints(minWidth: 180, maxWidth: 260),
      child: DropdownButtonFormField<String>(
        key: ValueKey(selected),
        initialValue: selected,
        isDense: true,
        decoration: const InputDecoration(
          labelText: '执行项目',
          border: OutlineInputBorder(),
          prefixIcon: Icon(Icons.folder_open),
        ),
        items: workspaces
            .map(
              (workspace) => DropdownMenuItem(
                value: workspace.key,
                child: Text(
                  workspace.key,
                  overflow: TextOverflow.ellipsis,
                ),
              ),
            )
            .toList(),
        onChanged: (value) {
          if (value != null) onChanged(value);
        },
      ),
    );
  }
}

class _RunStatusStrip extends StatelessWidget {
  final String taskId;
  final String commandType;
  final bool requiresApproval;
  final String selectedProjectKey;
  final bool evoforgeLearning;
  final List<DeviceTaskEvent> events;

  const _RunStatusStrip({
    required this.taskId,
    required this.commandType,
    required this.requiresApproval,
    required this.selectedProjectKey,
    required this.evoforgeLearning,
    required this.events,
  });

  @override
  Widget build(BuildContext context) {
    final orderedEvents = _sortedEvents(events);
    final latest = orderedEvents.isEmpty ? null : orderedEvents.last;
    final modelSteps = orderedEvents.where(_isAgentProgressEvent).length;
    final status = latest?.status ?? 'idle';
    final color = latest == null
        ? Colors.blueGrey
        : _eventColor(latest, Theme.of(context).colorScheme);
    return Wrap(
      spacing: 8,
      runSpacing: 8,
      crossAxisAlignment: WrapCrossAlignment.center,
      children: [
        _StatusPill(
          icon: latest == null
              ? Icons.radio_button_unchecked
              : _eventIcon(latest),
          color: color,
          label: _statusLabel(status),
        ),
        _StatusPill(
          icon: Icons.category,
          color: Theme.of(context).colorScheme.primary,
          label: _commandTypeLabel(commandType),
        ),
        if (commandType == DeviceCommandType.codexTask &&
            selectedProjectKey.isNotEmpty)
          _StatusPill(
            icon: Icons.folder_open,
            color: Colors.blueGrey,
            label: selectedProjectKey,
          ),
        if (commandType == DeviceCommandType.codexTask && evoforgeLearning)
          _StatusPill(
            icon: Icons.school,
            color: Theme.of(context).colorScheme.secondary,
            label: '记录变动脉络',
          ),
        _StatusPill(
          icon: Icons.psychology,
          color: Theme.of(context).colorScheme.tertiary,
          label: '$modelSteps 个模型步骤',
        ),
        _StatusPill(
          icon: Icons.receipt_long,
          color: Colors.blueGrey,
          label: '${orderedEvents.length} 个事件',
        ),
        if (requiresApproval)
          const _StatusPill(
            icon: Icons.rule,
            color: Colors.orange,
            label: '人工确认',
          ),
        if (taskId.isNotEmpty)
          Tooltip(
            message: taskId,
            child: _StatusPill(
              icon: Icons.tag,
              color: Colors.blueGrey,
              label: _shortId(taskId),
            ),
          ),
      ],
    );
  }
}

class _StatusPill extends StatelessWidget {
  final IconData icon;
  final Color color;
  final String label;

  const _StatusPill({
    required this.icon,
    required this.color,
    required this.label,
  });

  @override
  Widget build(BuildContext context) {
    return Container(
      padding: const EdgeInsets.symmetric(horizontal: 10, vertical: 6),
      decoration: BoxDecoration(
        color: color.withValues(alpha: 0.10),
        border: Border.all(color: color.withValues(alpha: 0.35)),
        borderRadius: BorderRadius.circular(8),
      ),
      child: Row(
        mainAxisSize: MainAxisSize.min,
        children: [
          Icon(icon, size: 16, color: color),
          const SizedBox(width: 6),
          Text(label, style: Theme.of(context).textTheme.labelMedium),
        ],
      ),
    );
  }
}

class _CommandInspector extends StatelessWidget {
  final List<AgentConversationTurn> turns;
  final String text;
  final String type;
  final bool requiresApproval;
  final String selectedProjectKey;
  final bool evoforgeLearning;
  final String threadId;
  final String taskId;
  final List<DeviceTaskEvent> events;

  const _CommandInspector({
    required this.turns,
    required this.text,
    required this.type,
    required this.requiresApproval,
    required this.selectedProjectKey,
    required this.evoforgeLearning,
    required this.threadId,
    required this.taskId,
    required this.events,
  });

  @override
  Widget build(BuildContext context) {
    return Column(
      crossAxisAlignment: CrossAxisAlignment.start,
      children: [
        _ConversationHistoryPanel(turns: turns),
        const SizedBox(height: 12),
        _CommandPreview(
          text: text,
          type: type,
          requiresApproval: requiresApproval,
          selectedProjectKey: selectedProjectKey,
          evoforgeLearning: evoforgeLearning,
          threadId: threadId,
        ),
        const SizedBox(height: 12),
        _EventSummaryPanel(taskId: taskId, events: events),
      ],
    );
  }
}

class _ConversationHistoryPanel extends StatelessWidget {
  final List<AgentConversationTurn> turns;

  const _ConversationHistoryPanel({required this.turns});

  @override
  Widget build(BuildContext context) {
    final recentTurns =
        turns.length <= 8 ? turns : turns.sublist(turns.length - 8);
    return Container(
      width: double.infinity,
      padding: const EdgeInsets.all(14),
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
                child:
                    Text('上下文', style: Theme.of(context).textTheme.titleSmall),
              ),
              Chip(
                avatar: const Icon(Icons.history, size: 16),
                label: Text('${turns.length}'),
              ),
            ],
          ),
          const SizedBox(height: 8),
          if (turns.isEmpty)
            Text(
              '暂无上下文',
              style: Theme.of(context).textTheme.bodyMedium,
            )
          else
            ...recentTurns.map((turn) {
              final isUser = turn.role == 'user';
              return Padding(
                padding: const EdgeInsets.only(bottom: 8),
                child: Row(
                  crossAxisAlignment: CrossAxisAlignment.start,
                  children: [
                    Tooltip(
                      message: _turnRoleLabel(turn.role),
                      child: Icon(
                        isUser ? Icons.person : Icons.smart_toy,
                        size: 18,
                        color: Theme.of(context).colorScheme.primary,
                      ),
                    ),
                    const SizedBox(width: 8),
                    Expanded(
                      child: Column(
                        crossAxisAlignment: CrossAxisAlignment.start,
                        children: [
                          Text(
                            _turnRoleLabel(turn.role),
                            style: Theme.of(context).textTheme.labelSmall,
                          ),
                          SelectableText(
                            turn.content,
                            maxLines: 5,
                          ),
                        ],
                      ),
                    ),
                  ],
                ),
              );
            }),
        ],
      ),
    );
  }
}

class _CommandPreview extends StatelessWidget {
  final String text;
  final String type;
  final bool requiresApproval;
  final String selectedProjectKey;
  final bool evoforgeLearning;
  final String threadId;

  const _CommandPreview({
    required this.text,
    required this.type,
    required this.requiresApproval,
    required this.selectedProjectKey,
    required this.evoforgeLearning,
    required this.threadId,
  });

  @override
  Widget build(BuildContext context) {
    final payload = {
      'type': type,
      'text': text.trim().isEmpty ? '...' : text.trim(),
      'requiresApproval': requiresApproval,
      'attributes': {
        'threadId': threadId,
        'conversationMode': 'thread-memory',
        if (type == DeviceCommandType.codexTask &&
            selectedProjectKey.isNotEmpty)
          'projectKey': selectedProjectKey,
        if (type == DeviceCommandType.codexTask && evoforgeLearning)
          'evoforgeLearning': {
            'enabled': true,
            'targetProjectKey': 'evoforge',
            'sourceProjectKey': selectedProjectKey,
            'recordChangeLineage': true,
          },
      },
    };

    return Container(
      width: double.infinity,
      decoration: BoxDecoration(
        border: Border.all(color: Theme.of(context).dividerColor),
        borderRadius: BorderRadius.circular(8),
      ),
      child: ExpansionTile(
        tilePadding: const EdgeInsets.symmetric(horizontal: 14),
        childrenPadding: const EdgeInsets.fromLTRB(14, 0, 14, 14),
        initiallyExpanded: false,
        title: Text('请求详情', style: Theme.of(context).textTheme.titleSmall),
        subtitle: Text(
          _commandTypeLabel(type),
          maxLines: 1,
          overflow: TextOverflow.ellipsis,
        ),
        children: [
          _JsonBlock(title: 'payload', value: payload, maxLines: 18),
        ],
      ),
    );
  }
}

class _EventSummaryPanel extends StatelessWidget {
  final String taskId;
  final List<DeviceTaskEvent> events;

  const _EventSummaryPanel({
    required this.taskId,
    required this.events,
  });

  @override
  Widget build(BuildContext context) {
    final orderedEvents = _sortedEvents(events);
    final latest = orderedEvents.isEmpty ? null : orderedEvents.last;
    return Container(
      width: double.infinity,
      padding: const EdgeInsets.all(14),
      decoration: BoxDecoration(
        border: Border.all(color: Theme.of(context).dividerColor),
        borderRadius: BorderRadius.circular(8),
      ),
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          Text('任务状态', style: Theme.of(context).textTheme.titleSmall),
          const SizedBox(height: 10),
          if (taskId.isEmpty)
            Text('暂无任务', style: Theme.of(context).textTheme.bodyMedium)
          else ...[
            _InfoRow(label: '任务', value: taskId),
            _InfoRow(
              label: '状态',
              value: latest == null ? '等待事件' : _statusLabel(latest.status),
            ),
            _InfoRow(label: '事件', value: '${orderedEvents.length}'),
            if (latest?.message.isNotEmpty == true)
              _InfoRow(label: '最近', value: latest!.message),
          ],
        ],
      ),
    );
  }
}

class _InfoRow extends StatelessWidget {
  final String label;
  final String value;

  const _InfoRow({required this.label, required this.value});

  @override
  Widget build(BuildContext context) {
    return Padding(
      padding: const EdgeInsets.only(bottom: 8),
      child: Row(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          SizedBox(
            width: 52,
            child: Text(
              label,
              style: Theme.of(context).textTheme.bodySmall?.copyWith(
                    color: Theme.of(context).colorScheme.onSurfaceVariant,
                    fontWeight: FontWeight.w600,
                  ),
            ),
          ),
          Expanded(
            child: SelectableText(
              value,
              maxLines: 4,
              style: Theme.of(context).textTheme.bodySmall,
            ),
          ),
        ],
      ),
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
    final orderedEvents = _sortedEvents(events);
    final agentStepCount = orderedEvents.where(_isAgentProgressEvent).length;
    return Column(
      crossAxisAlignment: CrossAxisAlignment.start,
      children: [
        Wrap(
          spacing: 8,
          runSpacing: 8,
          crossAxisAlignment: WrapCrossAlignment.center,
          children: [
            Text('执行过程', style: Theme.of(context).textTheme.titleMedium),
            if (agentStepCount > 0)
              Chip(
                avatar: const Icon(Icons.psychology, size: 16),
                label: Text('$agentStepCount 个模型步骤'),
              ),
            if (taskId.isNotEmpty)
              Tooltip(
                message: taskId,
                child: Chip(
                  avatar: const Icon(Icons.tag, size: 16),
                  label: Text(_shortId(taskId)),
                ),
              ),
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
            child: Center(
              child: Column(
                mainAxisSize: MainAxisSize.min,
                children: [
                  Icon(
                    Icons.pending_actions,
                    color: Theme.of(context).colorScheme.onSurfaceVariant,
                  ),
                  const SizedBox(height: 8),
                  const Text('等待任务'),
                ],
              ),
            ),
          )
        else
          ...orderedEvents.map((event) => _EventTile(event: event)),
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
    if (_isAgentProgressEvent(event)) {
      return _AgentProgressTile(event: event);
    }
    if (event.status == DeviceTaskStatus.completed) {
      return _CompletedEventTile(event: event);
    }
    return _GenericEventTile(event: event);
  }
}

class _AgentProgressTile extends StatelessWidget {
  final DeviceTaskEvent event;

  const _AgentProgressTile({required this.event});

  @override
  Widget build(BuildContext context) {
    final payload = event.payload;
    final phase = _textValue(payload['phase']) ?? 'progress';
    final observation = _asMap(payload['observation']);
    final isObserve = phase == 'observe';
    final success = observation['success'] != false;
    final color = isObserve
        ? success
            ? Colors.green
            : Colors.orange
        : Theme.of(context).colorScheme.primary;
    final title = isObserve ? '工具观察' : '模型规划';
    final step = _textValue(payload['step']) ?? _textValue(observation['step']);

    return _LogFrame(
      icon: isObserve ? Icons.build_circle : Icons.psychology,
      color: color,
      title: step == null ? title : '$title · 第 $step 步',
      subtitle: event.message,
      children: isObserve
          ? _buildObservationChildren(context, payload, observation)
          : _buildPlanChildren(context, payload),
    );
  }

  List<Widget> _buildPlanChildren(
    BuildContext context,
    Map<String, dynamic> payload,
  ) {
    final selectedRouteId = _textValue(payload['selectedRouteId']);
    final action = _asMap(payload['action']);
    final routes = _asMapList(payload['routes']);
    return [
      if (_textValue(payload['thought']) != null)
        _DetailLine(label: '思考', text: _textValue(payload['thought'])!),
      if (selectedRouteId != null)
        _DetailLine(label: '选择路线', text: selectedRouteId),
      if (action.isNotEmpty)
        _ToolCallView(
          tool: _textValue(action['tool']) ?? '未指定工具',
          args: action['args'],
        ),
      if (routes.isNotEmpty)
        _RoutesView(routes: routes, selectedRouteId: selectedRouteId),
      _RawEventDetails(event: event),
    ];
  }

  List<Widget> _buildObservationChildren(
    BuildContext context,
    Map<String, dynamic> payload,
    Map<String, dynamic> observation,
  ) {
    final routes = _asMapList(observation['availableRoutes']);
    final selectedRouteId = _textValue(observation['routeId']);
    return [
      if (_textValue(observation['thought']) != null)
        _DetailLine(label: '对应思考', text: _textValue(observation['thought'])!),
      if (selectedRouteId != null)
        _DetailLine(label: '路线', text: selectedRouteId),
      _ToolCallView(
        tool: _textValue(observation['tool']) ?? '未知工具',
        args: observation['args'],
      ),
      if (_hasValue(observation['output']))
        _JsonBlock(title: '工具输出', value: observation['output']),
      if (_textValue(observation['error']) != null)
        _DetailLine(
          label: '错误',
          text: _textValue(observation['error'])!,
          color: Colors.red,
        ),
      if (_hasValue(observation['meta']))
        _JsonBlock(title: '元数据', value: observation['meta'], maxLines: 8),
      if (routes.isNotEmpty)
        _RoutesView(routes: routes, selectedRouteId: selectedRouteId),
      _RawEventDetails(event: event),
    ];
  }
}

class _CompletedEventTile extends StatelessWidget {
  final DeviceTaskEvent event;

  const _CompletedEventTile({required this.event});

  @override
  Widget build(BuildContext context) {
    final agentRun = _asMap(event.payload['agentRun']);
    final projectLearning = _asMap(event.payload['projectLearning']);
    final output = event.output.isNotEmpty
        ? event.output
        : _textValue(agentRun['output']) ?? '';
    return _LogFrame(
      icon: Icons.check_circle,
      color: Colors.green,
      title: '执行完成',
      subtitle: event.message,
      children: [
        if (output.isNotEmpty) _JsonBlock(title: '最终输出', value: output),
        if (projectLearning.isNotEmpty)
          _JsonBlock(title: 'EvoForge 学习记录', value: projectLearning),
        if (agentRun.isNotEmpty) _AgentRunSummary(agentRun: agentRun),
        _RawEventDetails(event: event),
      ],
    );
  }
}

class _GenericEventTile extends StatelessWidget {
  final DeviceTaskEvent event;

  const _GenericEventTile({required this.event});

  @override
  Widget build(BuildContext context) {
    final color = _eventColor(event, Theme.of(context).colorScheme);
    return _LogFrame(
      icon: _eventIcon(event),
      color: color,
      title: _eventTitle(event),
      subtitle: event.message.isEmpty ? event.createdAt : event.message,
      children: [
        if (event.output.isNotEmpty)
          _JsonBlock(title: '输出', value: event.output),
        if (event.payload.isNotEmpty) _RawEventDetails(event: event),
      ],
    );
  }
}

class _LogFrame extends StatelessWidget {
  final IconData icon;
  final Color color;
  final String title;
  final String subtitle;
  final List<Widget> children;

  const _LogFrame({
    required this.icon,
    required this.color,
    required this.title,
    required this.subtitle,
    required this.children,
  });

  @override
  Widget build(BuildContext context) {
    final scheme = Theme.of(context).colorScheme;
    return Padding(
      padding: const EdgeInsets.only(bottom: 8),
      child: Container(
        width: double.infinity,
        padding: const EdgeInsets.all(12),
        decoration: BoxDecoration(
          border: Border.all(color: Theme.of(context).dividerColor),
          borderRadius: BorderRadius.circular(8),
        ),
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            Row(
              crossAxisAlignment: CrossAxisAlignment.start,
              children: [
                Container(
                  width: 30,
                  height: 30,
                  decoration: BoxDecoration(
                    color: color.withValues(alpha: 0.12),
                    borderRadius: BorderRadius.circular(15),
                  ),
                  child: Icon(icon, size: 18, color: color),
                ),
                const SizedBox(width: 10),
                Expanded(
                  child: Column(
                    crossAxisAlignment: CrossAxisAlignment.start,
                    children: [
                      Text(title,
                          style: Theme.of(context).textTheme.titleSmall),
                      if (subtitle.isNotEmpty)
                        Padding(
                          padding: const EdgeInsets.only(top: 2),
                          child: Text(
                            subtitle,
                            style: Theme.of(context)
                                .textTheme
                                .bodySmall
                                ?.copyWith(color: scheme.onSurfaceVariant),
                          ),
                        ),
                    ],
                  ),
                ),
              ],
            ),
            if (children.isNotEmpty) ...[
              const SizedBox(height: 10),
              Padding(
                padding: const EdgeInsets.only(left: 40),
                child: Column(
                  crossAxisAlignment: CrossAxisAlignment.start,
                  children: children,
                ),
              ),
            ],
          ],
        ),
      ),
    );
  }
}

class _DetailLine extends StatelessWidget {
  final String label;
  final String text;
  final Color? color;

  const _DetailLine({
    required this.label,
    required this.text,
    this.color,
  });

  @override
  Widget build(BuildContext context) {
    final labelStyle = Theme.of(context).textTheme.bodySmall?.copyWith(
          color: Theme.of(context).colorScheme.onSurfaceVariant,
          fontWeight: FontWeight.w600,
        );
    return Padding(
      padding: const EdgeInsets.only(bottom: 8),
      child: Row(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          SizedBox(width: 76, child: Text(label, style: labelStyle)),
          Expanded(
            child: SelectableText(
              text,
              maxLines: 8,
              style: Theme.of(context).textTheme.bodySmall?.copyWith(
                    color: color,
                  ),
            ),
          ),
        ],
      ),
    );
  }
}

class _ToolCallView extends StatelessWidget {
  final String tool;
  final Object? args;

  const _ToolCallView({required this.tool, this.args});

  @override
  Widget build(BuildContext context) {
    return Padding(
      padding: const EdgeInsets.only(bottom: 8),
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          Wrap(
            spacing: 8,
            runSpacing: 8,
            crossAxisAlignment: WrapCrossAlignment.center,
            children: [
              Chip(
                avatar: const Icon(Icons.handyman, size: 16),
                label: Text(tool),
              ),
            ],
          ),
          if (_hasValue(args))
            Padding(
              padding: const EdgeInsets.only(top: 6),
              child: _JsonBlock(title: '工具入参', value: args, maxLines: 10),
            ),
        ],
      ),
    );
  }
}

class _RoutesView extends StatelessWidget {
  final List<Map<String, dynamic>> routes;
  final String? selectedRouteId;

  const _RoutesView({required this.routes, this.selectedRouteId});

  @override
  Widget build(BuildContext context) {
    return Padding(
      padding: const EdgeInsets.only(bottom: 8),
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          Text('路线', style: Theme.of(context).textTheme.labelMedium),
          const SizedBox(height: 6),
          ...routes.map((route) {
            final id = _textValue(route['id']) ?? '-';
            final selected = id == selectedRouteId;
            final status = _textValue(route['status']) ?? 'unknown';
            final rationale = _textValue(route['rationale']);
            final next = _textValue(route['next']);
            final scheme = Theme.of(context).colorScheme;
            return Container(
              width: double.infinity,
              margin: const EdgeInsets.only(bottom: 6),
              padding: const EdgeInsets.all(8),
              decoration: BoxDecoration(
                color: selected
                    ? scheme.primaryContainer.withValues(alpha: 0.45)
                    : scheme.surface,
                border: Border.all(
                  color: selected
                      ? scheme.primary
                      : Theme.of(context).dividerColor,
                ),
                borderRadius: BorderRadius.circular(8),
              ),
              child: Column(
                crossAxisAlignment: CrossAxisAlignment.start,
                children: [
                  Wrap(
                    spacing: 8,
                    runSpacing: 4,
                    crossAxisAlignment: WrapCrossAlignment.center,
                    children: [
                      Chip(label: Text(id)),
                      Chip(label: Text(status)),
                      if (selected)
                        const Chip(
                          avatar: Icon(Icons.check, size: 16),
                          label: Text('当前选择'),
                        ),
                    ],
                  ),
                  if (rationale != null)
                    Padding(
                      padding: const EdgeInsets.only(top: 4),
                      child: SelectableText(rationale, maxLines: 4),
                    ),
                  if (next != null)
                    Padding(
                      padding: const EdgeInsets.only(top: 4),
                      child: SelectableText('下一步: $next', maxLines: 4),
                    ),
                ],
              ),
            );
          }),
        ],
      ),
    );
  }
}

class _JsonBlock extends StatelessWidget {
  final String title;
  final Object? value;
  final int maxLines;

  const _JsonBlock({
    required this.title,
    required this.value,
    this.maxLines = 16,
  });

  @override
  Widget build(BuildContext context) {
    final scheme = Theme.of(context).colorScheme;
    return Container(
      width: double.infinity,
      margin: const EdgeInsets.only(bottom: 8),
      padding: const EdgeInsets.all(10),
      decoration: BoxDecoration(
        color: scheme.surface,
        border: Border.all(color: Theme.of(context).dividerColor),
        borderRadius: BorderRadius.circular(8),
      ),
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          Text(title, style: Theme.of(context).textTheme.labelMedium),
          const SizedBox(height: 6),
          SelectableText(
            _prettyJson(value),
            maxLines: maxLines,
            style: Theme.of(context).textTheme.bodySmall?.copyWith(
                  fontFamily: 'monospace',
                ),
          ),
        ],
      ),
    );
  }
}

class _RawEventDetails extends StatelessWidget {
  final DeviceTaskEvent event;

  const _RawEventDetails({required this.event});

  @override
  Widget build(BuildContext context) {
    return ExpansionTile(
      tilePadding: EdgeInsets.zero,
      childrenPadding: EdgeInsets.zero,
      title: Text(
        '原始事件',
        style: Theme.of(context).textTheme.labelMedium,
      ),
      children: [
        _JsonBlock(
          title: 'payload',
          value: {
            'type': event.type,
            'status': event.status,
            'level': event.level,
            'message': event.message,
            'output': event.output,
            'payload': event.payload,
            'createdAt': event.createdAt,
          },
          maxLines: 24,
        ),
      ],
    );
  }
}

class _AgentRunSummary extends StatelessWidget {
  final Map<String, dynamic> agentRun;

  const _AgentRunSummary({required this.agentRun});

  @override
  Widget build(BuildContext context) {
    final routes = _asMapList(agentRun['routes']);
    final observations = _asMapList(agentRun['observations']);
    final stopReason = _textValue(agentRun['stopReason']);
    return ExpansionTile(
      tilePadding: EdgeInsets.zero,
      childrenPadding: EdgeInsets.zero,
      title: Text(
        '运行摘要',
        style: Theme.of(context).textTheme.labelMedium,
      ),
      subtitle: Text(
        [
          if (stopReason != null) '停止原因 $stopReason',
          '${routes.length} 条路线',
          '${observations.length} 次观察',
        ].join(' · '),
      ),
      children: [
        if (routes.isNotEmpty) _RoutesView(routes: routes),
        if (observations.isNotEmpty)
          _JsonBlock(title: '观察记录', value: observations, maxLines: 24),
      ],
    );
  }
}

bool _isAgentProgressEvent(DeviceTaskEvent event) {
  return event.type == DeviceTaskStatus.agentProgress ||
      event.status == DeviceTaskStatus.agentProgress;
}

String _eventTitle(DeviceTaskEvent event) {
  return switch (event.status) {
    DeviceTaskStatus.queued => '任务排队',
    DeviceTaskStatus.accepted => '任务接收',
    DeviceTaskStatus.running => '任务执行中',
    DeviceTaskStatus.needsApproval => '等待确认',
    DeviceTaskStatus.approved => '已批准',
    DeviceTaskStatus.rejected => '已拒绝',
    DeviceTaskStatus.failed => '执行失败',
    DeviceTaskStatus.clientResponse => '客户端响应',
    _ => '${event.type} · ${event.status}',
  };
}

IconData _eventIcon(DeviceTaskEvent event) {
  return switch (event.status) {
    DeviceTaskStatus.completed => Icons.check_circle,
    DeviceTaskStatus.failed => Icons.error,
    DeviceTaskStatus.rejected => Icons.block,
    DeviceTaskStatus.running => Icons.play_circle,
    DeviceTaskStatus.needsApproval => Icons.rule,
    DeviceTaskStatus.queued => Icons.schedule,
    _ => Icons.circle,
  };
}

Color _eventColor(DeviceTaskEvent event, ColorScheme scheme) {
  if (event.level.toLowerCase() == 'error' ||
      event.status == DeviceTaskStatus.failed) {
    return Colors.red;
  }
  if (event.level.toLowerCase() == 'warn' ||
      event.status == DeviceTaskStatus.needsApproval) {
    return Colors.orange;
  }
  if (event.status == DeviceTaskStatus.completed) return Colors.green;
  if (event.status == DeviceTaskStatus.running) return scheme.primary;
  return Colors.blueGrey;
}

String? _textValue(Object? value) {
  if (value == null) return null;
  final text = value.toString().trim();
  return text.isEmpty ? null : text;
}

bool _hasValue(Object? value) {
  if (value == null) return false;
  if (value is String) return value.trim().isNotEmpty;
  if (value is Map) return value.isNotEmpty;
  if (value is Iterable) return value.isNotEmpty;
  return true;
}

Map<String, dynamic> _asMap(Object? value) {
  if (value is Map) {
    return value.map((key, item) => MapEntry(key.toString(), item));
  }
  return {};
}

List<Map<String, dynamic>> _asMapList(Object? value) {
  if (value is Iterable) {
    return value
        .map(_asMap)
        .where((item) => item.isNotEmpty)
        .toList(growable: false);
  }
  return const [];
}

String _prettyJson(Object? value) {
  if (value == null) return '';
  if (value is String) return value;
  try {
    return const JsonEncoder.withIndent('  ').convert(value);
  } catch (_) {
    return value.toString();
  }
}
