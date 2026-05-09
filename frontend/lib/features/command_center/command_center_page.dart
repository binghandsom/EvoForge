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
  static const int taskEventPageSize = 80;

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
  String answeringQuestionId = '';
  bool loadingConversations = false;
  bool loadingTurns = false;
  bool loadingDeviceStatus = false;
  bool requiresApproval = false;
  bool evoforgeLearning = false;
  bool evoforgeTester = false;
  bool eventHasMoreBefore = false;
  bool loadingOlderEvents = false;
  bool loadedOlderEventPages = false;
  String eventBeforeCursor = '';
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
    if (_usesProjectSelector(commandType) &&
        codexWorkspaces.isNotEmpty &&
        selectedProjectKey.isEmpty) {
      showMessage('请选择执行项目');
      return;
    }
    setState(() {
      sending = true;
      taskId = '';
      events = [];
      eventBeforeCursor = '';
      eventHasMoreBefore = false;
      loadedOlderEventPages = false;
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
      final page = await widget.api.loadTaskEventPage(
        taskId: taskId,
        limit: taskEventPageSize,
      );
      if (!mounted) return;
      final ordered = _mergeEvents(events, page.items);
      setState(() {
        events = ordered;
        if (!loadedOlderEventPages) {
          eventBeforeCursor = page.beforeCursor;
          eventHasMoreBefore = page.hasMoreBefore;
        }
      });
      if (ordered.isNotEmpty && isTerminalStatus(ordered.last.status)) {
        stopPolling();
        await loadConversationTurns(threadId);
        await loadConversations(keepSelection: true);
      }
    } catch (e) {
      showMessage('刷新事件失败: $e');
    }
  }

  Future<void> loadOlderEvents() async {
    if (taskId.isEmpty ||
        loadingOlderEvents ||
        !eventHasMoreBefore ||
        eventBeforeCursor.isEmpty) {
      return;
    }
    setState(() => loadingOlderEvents = true);
    try {
      final page = await widget.api.loadTaskEventPage(
        taskId: taskId,
        limit: taskEventPageSize,
        before: eventBeforeCursor,
      );
      if (!mounted) return;
      setState(() {
        events = _mergeEvents(page.items, events);
        eventBeforeCursor = page.beforeCursor;
        eventHasMoreBefore = page.hasMoreBefore;
        loadedOlderEventPages = true;
      });
    } catch (e) {
      showMessage('加载更早事件失败: $e');
    } finally {
      if (mounted) setState(() => loadingOlderEvents = false);
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

  Future<void> answerCodexQuestion({
    required String questionId,
    required String answer,
    required String questionTaskId,
  }) async {
    final text = answer.trim();
    if (text.isEmpty) {
      showMessage('请输入回复内容');
      return;
    }
    setState(() => answeringQuestionId = questionId);
    try {
      await widget.api.answerCodexQuestion(
        questionId: questionId,
        answer: text,
        questionTaskId: questionTaskId,
      );
      showMessage('已发送回复');
      if (!widget.api.usesMessageBus) {
        await refreshEvents();
      }
    } catch (e) {
      showMessage('回复失败: $e');
    } finally {
      if (mounted) setState(() => answeringQuestionId = '');
    }
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
      eventBeforeCursor = '';
      eventHasMoreBefore = false;
      loadedOlderEventPages = false;
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
      eventBeforeCursor = '';
      eventHasMoreBefore = false;
      loadedOlderEventPages = false;
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
    if (_usesProjectSelector(commandType)) {
      if (selectedProjectKey.isNotEmpty) {
        attributes['projectKey'] = selectedProjectKey;
      }
    }
    if (commandType == DeviceCommandType.codexTask) {
      if (evoforgeLearning) {
        attributes['evoforgeLearning'] = {
          'enabled': true,
          'targetProjectKey': 'evoforge',
          'sourceProjectKey': selectedProjectKey,
          'recordChangeLineage': true,
          'contextPolicy': {
            'mode': 'focused',
            'maxFacts': 5,
            'maxChars': 3200,
            'maxCharsPerFact': 700,
          },
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
    if (commandType == DeviceCommandType.testerTask ||
        (commandType == DeviceCommandType.codexTask && evoforgeTester)) {
      attributes['evoforgeTester'] = {
        'enabled': true,
        'contextPolicy': {
          'mode': 'focused',
          'maxFacts': 4,
          'maxChars': 2400,
          'maxCharsPerFact': 600,
        },
      };
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
                evoforgeTester: evoforgeTester,
                sending: sending,
                approving: approving,
                answeringQuestionId: answeringQuestionId,
                eventHasMoreBefore: eventHasMoreBefore,
                loadingOlderEvents: loadingOlderEvents,
                onInputChanged: () => setState(() {}),
                onCommandTypeChanged: (value) =>
                    setState(() => commandType = value),
                onRequiresApprovalChanged: (value) =>
                    setState(() => requiresApproval = value),
                onProjectKeyChanged: (value) =>
                    setState(() => selectedProjectKey = value),
                onEvoforgeLearningChanged: (value) =>
                    setState(() => evoforgeLearning = value),
                onEvoforgeTesterChanged: (value) =>
                    setState(() => evoforgeTester = value),
                onSend: send,
                onApprove: latestNeedsApproval ? approveLatestTask : null,
                onReject: latestNeedsApproval ? rejectLatestTask : null,
                onAnswerQuestion: answerCodexQuestion,
                onLoadOlderEvents: loadOlderEvents,
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

String _formatEventTime(String value) {
  final parsed = DateTime.tryParse(value);
  final local = parsed?.toLocal();
  if (local == null) return value.length > 5 ? value.substring(0, 5) : value;
  final hour = local.hour.toString().padLeft(2, '0');
  final minute = local.minute.toString().padLeft(2, '0');
  final second = local.second.toString().padLeft(2, '0');
  return '$hour:$minute:$second';
}

bool _usesProjectSelector(String type) {
  return type == DeviceCommandType.codexTask ||
      type == DeviceCommandType.testerTask;
}

String _commandTypeLabel(String type) {
  return switch (type) {
    DeviceCommandType.codexTask => 'Codex',
    DeviceCommandType.testerTask => '测试员',
    DeviceCommandType.humanResponse => '人工回复',
    DeviceCommandType.naturalLanguageTask => '普通任务',
    DeviceCommandType.approvalDecision => '确认决策',
    DeviceCommandType.clientRequest => '客户端请求',
    _ => type,
  };
}

IconData _commandTypeIcon(String type) {
  return switch (type) {
    DeviceCommandType.codexTask => Icons.code,
    DeviceCommandType.testerTask => Icons.fact_check,
    DeviceCommandType.humanResponse => Icons.mark_chat_read,
    DeviceCommandType.approvalDecision => Icons.rule,
    DeviceCommandType.clientRequest => Icons.sync_alt,
    _ => Icons.chat,
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
    DeviceTaskStatus.needsInput => '等待回复',
    DeviceTaskStatus.inputReceived => '已回复',
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

List<DeviceTaskEvent> _mergeEvents(
  Iterable<DeviceTaskEvent> current,
  Iterable<DeviceTaskEvent> incoming,
) {
  final byId = <String, DeviceTaskEvent>{};
  for (final event in current) {
    if (event.eventId.isNotEmpty) {
      byId[event.eventId] = event;
    }
  }
  for (final event in incoming) {
    if (event.eventId.isNotEmpty) {
      byId[event.eventId] = event;
    }
  }
  return _sortedEvents(byId.values);
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
    final scheme = Theme.of(context).colorScheme;
    return Container(
      decoration: BoxDecoration(
        color: scheme.surface,
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
                        selectedTileColor:
                            scheme.primary.withValues(alpha: 0.08),
                        leading: Icon(
                          selected ? Icons.forum : Icons.chat_bubble_outline,
                          color: selected ? scheme.primary : null,
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
  final bool evoforgeTester;
  final bool sending;
  final bool approving;
  final String answeringQuestionId;
  final bool eventHasMoreBefore;
  final bool loadingOlderEvents;
  final VoidCallback onInputChanged;
  final ValueChanged<String> onCommandTypeChanged;
  final ValueChanged<bool> onRequiresApprovalChanged;
  final ValueChanged<String> onProjectKeyChanged;
  final ValueChanged<bool> onEvoforgeLearningChanged;
  final ValueChanged<bool> onEvoforgeTesterChanged;
  final VoidCallback onSend;
  final VoidCallback? onApprove;
  final VoidCallback? onReject;
  final VoidCallback onLoadOlderEvents;
  final Future<void> Function({
    required String questionId,
    required String answer,
    required String questionTaskId,
  }) onAnswerQuestion;

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
    required this.evoforgeTester,
    required this.sending,
    required this.approving,
    required this.answeringQuestionId,
    required this.eventHasMoreBefore,
    required this.loadingOlderEvents,
    required this.onInputChanged,
    required this.onCommandTypeChanged,
    required this.onRequiresApprovalChanged,
    required this.onProjectKeyChanged,
    required this.onEvoforgeLearningChanged,
    required this.onEvoforgeTesterChanged,
    required this.onSend,
    required this.onApprove,
    required this.onReject,
    required this.onLoadOlderEvents,
    required this.onAnswerQuestion,
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
          evoforgeTester: evoforgeTester,
          sending: sending,
          approving: approving,
          answeringQuestionId: answeringQuestionId,
          eventHasMoreBefore: eventHasMoreBefore,
          loadingOlderEvents: loadingOlderEvents,
          onInputChanged: onInputChanged,
          onCommandTypeChanged: onCommandTypeChanged,
          onRequiresApprovalChanged: onRequiresApprovalChanged,
          onProjectKeyChanged: onProjectKeyChanged,
          onEvoforgeLearningChanged: onEvoforgeLearningChanged,
          onEvoforgeTesterChanged: onEvoforgeTesterChanged,
          onSend: onSend,
          onApprove: onApprove,
          onReject: onReject,
          onLoadOlderEvents: onLoadOlderEvents,
          onAnswerQuestion: onAnswerQuestion,
        );
        final inspector = _CommandInspector(
          turns: turns,
          text: inputController.text,
          type: commandType,
          requiresApproval: requiresApproval,
          selectedProjectKey: selectedProjectKey,
          evoforgeLearning: evoforgeLearning,
          evoforgeTester: evoforgeTester,
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
  final bool evoforgeTester;
  final bool sending;
  final bool approving;
  final String answeringQuestionId;
  final bool eventHasMoreBefore;
  final bool loadingOlderEvents;
  final VoidCallback onInputChanged;
  final ValueChanged<String> onCommandTypeChanged;
  final ValueChanged<bool> onRequiresApprovalChanged;
  final ValueChanged<String> onProjectKeyChanged;
  final ValueChanged<bool> onEvoforgeLearningChanged;
  final ValueChanged<bool> onEvoforgeTesterChanged;
  final VoidCallback onSend;
  final VoidCallback? onApprove;
  final VoidCallback? onReject;
  final VoidCallback onLoadOlderEvents;
  final Future<void> Function({
    required String questionId,
    required String answer,
    required String questionTaskId,
  }) onAnswerQuestion;

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
    required this.evoforgeTester,
    required this.sending,
    required this.approving,
    required this.answeringQuestionId,
    required this.eventHasMoreBefore,
    required this.loadingOlderEvents,
    required this.onInputChanged,
    required this.onCommandTypeChanged,
    required this.onRequiresApprovalChanged,
    required this.onProjectKeyChanged,
    required this.onEvoforgeLearningChanged,
    required this.onEvoforgeTesterChanged,
    required this.onSend,
    required this.onApprove,
    required this.onReject,
    required this.onLoadOlderEvents,
    required this.onAnswerQuestion,
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
          evoforgeTester: evoforgeTester,
          sending: sending,
          onInputChanged: onInputChanged,
          onCommandTypeChanged: onCommandTypeChanged,
          onRequiresApprovalChanged: onRequiresApprovalChanged,
          onProjectKeyChanged: onProjectKeyChanged,
          onEvoforgeLearningChanged: onEvoforgeLearningChanged,
          onEvoforgeTesterChanged: onEvoforgeTesterChanged,
          onSend: onSend,
        ),
        const SizedBox(height: 12),
        _RunStatusStrip(
          taskId: taskId,
          commandType: commandType,
          requiresApproval: requiresApproval,
          selectedProjectKey: selectedProjectKey,
          evoforgeLearning: evoforgeLearning,
          evoforgeTester: evoforgeTester,
          events: events,
        ),
        const SizedBox(height: 12),
        _TimelinePanel(
          taskId: taskId,
          events: events,
          answeringQuestionId: answeringQuestionId,
          approving: approving,
          hasMoreBefore: eventHasMoreBefore,
          loadingOlderEvents: loadingOlderEvents,
          onApprove: onApprove,
          onReject: onReject,
          onLoadOlderEvents: onLoadOlderEvents,
          onAnswerQuestion: onAnswerQuestion,
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
  final bool evoforgeTester;
  final bool sending;
  final VoidCallback onInputChanged;
  final ValueChanged<String> onCommandTypeChanged;
  final ValueChanged<bool> onRequiresApprovalChanged;
  final ValueChanged<String> onProjectKeyChanged;
  final ValueChanged<bool> onEvoforgeLearningChanged;
  final ValueChanged<bool> onEvoforgeTesterChanged;
  final VoidCallback onSend;

  const _CommandComposer({
    required this.inputController,
    required this.commandType,
    required this.requiresApproval,
    required this.threadId,
    required this.codexWorkspaces,
    required this.selectedProjectKey,
    required this.evoforgeLearning,
    required this.evoforgeTester,
    required this.sending,
    required this.onInputChanged,
    required this.onCommandTypeChanged,
    required this.onRequiresApprovalChanged,
    required this.onProjectKeyChanged,
    required this.onEvoforgeLearningChanged,
    required this.onEvoforgeTesterChanged,
    required this.onSend,
  });

  @override
  Widget build(BuildContext context) {
    final scheme = Theme.of(context).colorScheme;
    return Container(
      width: double.infinity,
      padding: const EdgeInsets.all(14),
      decoration: BoxDecoration(
        color: scheme.surface,
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
                  '任务输入',
                  style: Theme.of(context).textTheme.titleMedium?.copyWith(
                        fontWeight: FontWeight.w700,
                      ),
                ),
              ),
              _StatusPill(
                icon: _commandTypeIcon(commandType),
                color: scheme.primary,
                label: _commandTypeLabel(commandType),
              ),
            ],
          ),
          const SizedBox(height: 12),
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
            ButtonSegment(
              value: DeviceCommandType.testerTask,
              icon: Icon(Icons.fact_check),
              label: Text('测试员'),
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
        if (_usesProjectSelector(commandType))
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
        if (commandType == DeviceCommandType.codexTask)
          FilterChip(
            avatar: const Icon(Icons.fact_check, size: 18),
            label: const Text('EvoForge 测试员'),
            selected: evoforgeTester,
            onSelected: onEvoforgeTesterChanged,
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
    final canSend = inputController.text.trim().isNotEmpty && !sending;
    return SizedBox(
      height: 44,
      child: FilledButton.icon(
        onPressed: canSend ? onSend : null,
        icon: sending
            ? const SizedBox(
                width: 16,
                height: 16,
                child: CircularProgressIndicator(strokeWidth: 2),
              )
            : const Icon(Icons.send),
        label: const Text('发送任务'),
      ),
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
                child: Tooltip(
                  message:
                      workspace.path.isEmpty ? workspace.key : workspace.path,
                  child: Row(
                    children: [
                      Expanded(
                        child: Text(
                          workspace.key,
                          overflow: TextOverflow.ellipsis,
                        ),
                      ),
                      if (workspace.path.isNotEmpty) ...[
                        const SizedBox(width: 8),
                        Icon(
                          Icons.info_outline,
                          size: 14,
                          color: Theme.of(context).colorScheme.onSurfaceVariant,
                        ),
                      ],
                    ],
                  ),
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
  final bool evoforgeTester;
  final List<DeviceTaskEvent> events;

  const _RunStatusStrip({
    required this.taskId,
    required this.commandType,
    required this.requiresApproval,
    required this.selectedProjectKey,
    required this.evoforgeLearning,
    required this.evoforgeTester,
    required this.events,
  });

  @override
  Widget build(BuildContext context) {
    final scheme = Theme.of(context).colorScheme;
    final orderedEvents = _sortedEvents(events);
    final latest = orderedEvents.isEmpty ? null : orderedEvents.last;
    final modelSteps = orderedEvents.where(_isAgentProgressEvent).length;
    final status = latest?.status ?? 'idle';
    final color =
        latest == null ? Colors.blueGrey : _eventColor(latest, scheme);
    final chips = [
      _StatusPill(
        icon: _commandTypeIcon(commandType),
        color: scheme.primary,
        label: _commandTypeLabel(commandType),
      ),
      if (_usesProjectSelector(commandType) && selectedProjectKey.isNotEmpty)
        _StatusPill(
          icon: Icons.folder_open,
          color: Colors.blueGrey,
          label: selectedProjectKey,
        ),
      if (commandType == DeviceCommandType.codexTask && evoforgeLearning)
        _StatusPill(
          icon: Icons.school,
          color: scheme.secondary,
          label: '记录变动脉络',
        ),
      if (commandType == DeviceCommandType.testerTask ||
          (commandType == DeviceCommandType.codexTask && evoforgeTester))
        _StatusPill(
          icon: Icons.fact_check,
          color: scheme.primary,
          label: commandType == DeviceCommandType.testerTask ? '测试员' : '测试员跟跑',
        ),
      _StatusPill(
        icon: Icons.psychology,
        color: scheme.tertiary,
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
    ];
    final title = latest == null ? '等待任务' : _eventTitle(latest);
    final subtitle = latest?.message.isNotEmpty == true
        ? latest!.message
        : taskId.isEmpty
            ? '输入任务后，这里会显示运行状态'
            : '等待事件流返回';

    return Container(
      width: double.infinity,
      padding: const EdgeInsets.all(12),
      decoration: BoxDecoration(
        color: color.withValues(alpha: 0.06),
        border: Border.all(color: color.withValues(alpha: 0.24)),
        borderRadius: BorderRadius.circular(8),
      ),
      child: LayoutBuilder(
        builder: (context, constraints) {
          final statusBlock = Row(
            mainAxisSize: MainAxisSize.min,
            children: [
              Container(
                width: 38,
                height: 38,
                decoration: BoxDecoration(
                  color: color.withValues(alpha: 0.14),
                  borderRadius: BorderRadius.circular(8),
                ),
                child: Icon(
                  latest == null
                      ? Icons.radio_button_unchecked
                      : _eventIcon(latest),
                  color: color,
                  size: 22,
                ),
              ),
              const SizedBox(width: 10),
              Flexible(
                child: Column(
                  crossAxisAlignment: CrossAxisAlignment.start,
                  children: [
                    Text(
                      _statusLabel(status),
                      style: Theme.of(context).textTheme.titleSmall?.copyWith(
                            fontWeight: FontWeight.w700,
                          ),
                    ),
                    const SizedBox(height: 2),
                    Text(
                      title,
                      maxLines: 1,
                      overflow: TextOverflow.ellipsis,
                      style: Theme.of(context).textTheme.bodySmall?.copyWith(
                            color: scheme.onSurfaceVariant,
                          ),
                    ),
                  ],
                ),
              ),
            ],
          );

          final chipBlock = Wrap(
            spacing: 8,
            runSpacing: 8,
            alignment: WrapAlignment.end,
            children: chips,
          );

          return Column(
            crossAxisAlignment: CrossAxisAlignment.start,
            children: [
              if (constraints.maxWidth < 720) ...[
                statusBlock,
                const SizedBox(height: 10),
                chipBlock,
              ] else
                Row(
                  crossAxisAlignment: CrossAxisAlignment.center,
                  children: [
                    Expanded(child: statusBlock),
                    const SizedBox(width: 12),
                    Flexible(child: chipBlock),
                  ],
                ),
              if (subtitle.isNotEmpty) ...[
                const SizedBox(height: 10),
                Text(
                  subtitle,
                  maxLines: 2,
                  overflow: TextOverflow.ellipsis,
                  style: Theme.of(context).textTheme.bodySmall,
                ),
              ],
            ],
          );
        },
      ),
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
  final bool evoforgeTester;
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
    required this.evoforgeTester,
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
          evoforgeTester: evoforgeTester,
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
  final bool evoforgeTester;
  final String threadId;

  const _CommandPreview({
    required this.text,
    required this.type,
    required this.requiresApproval,
    required this.selectedProjectKey,
    required this.evoforgeLearning,
    required this.evoforgeTester,
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
        if (_usesProjectSelector(type) && selectedProjectKey.isNotEmpty)
          'projectKey': selectedProjectKey,
        if (type == DeviceCommandType.codexTask && evoforgeLearning)
          'evoforgeLearning': {
            'enabled': true,
            'targetProjectKey': 'evoforge',
            'sourceProjectKey': selectedProjectKey,
            'recordChangeLineage': true,
            'contextPolicy': {
              'mode': 'focused',
              'maxFacts': 5,
              'maxChars': 3200,
              'maxCharsPerFact': 700,
            },
            'learningScopes': [
              'task-intent',
              'change-lineage',
              'codex-output',
              'errors',
              'skills',
              'project-facts',
            ],
          },
        if (type == DeviceCommandType.testerTask ||
            (type == DeviceCommandType.codexTask && evoforgeTester))
          'evoforgeTester': {
            'enabled': true,
            'contextPolicy': {
              'mode': 'focused',
              'maxFacts': 4,
              'maxChars': 2400,
              'maxCharsPerFact': 600,
            },
          },
      },
    };

    return Container(
      width: double.infinity,
      decoration: BoxDecoration(
        border: Border.all(color: Theme.of(context).dividerColor),
        borderRadius: BorderRadius.circular(8),
      ),
      child: _LazyExpansionTile(
        title: Padding(
          padding: const EdgeInsets.symmetric(horizontal: 14),
          child: Text('请求详情', style: Theme.of(context).textTheme.titleSmall),
        ),
        subtitle: Text(
          _commandTypeLabel(type),
          maxLines: 1,
          overflow: TextOverflow.ellipsis,
        ),
        childrenBuilder: (_) => Padding(
          padding: const EdgeInsets.fromLTRB(14, 0, 14, 14),
          child: _JsonBlock(title: 'payload', value: payload, maxLines: 18),
        ),
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
  final String answeringQuestionId;
  final bool approving;
  final bool hasMoreBefore;
  final bool loadingOlderEvents;
  final VoidCallback? onApprove;
  final VoidCallback? onReject;
  final VoidCallback onLoadOlderEvents;
  final Future<void> Function({
    required String questionId,
    required String answer,
    required String questionTaskId,
  }) onAnswerQuestion;

  const _TimelinePanel({
    required this.taskId,
    required this.events,
    required this.answeringQuestionId,
    required this.approving,
    required this.hasMoreBefore,
    required this.loadingOlderEvents,
    required this.onApprove,
    required this.onReject,
    required this.onLoadOlderEvents,
    required this.onAnswerQuestion,
  });

  @override
  Widget build(BuildContext context) {
    final scheme = Theme.of(context).colorScheme;
    final orderedEvents = _sortedEvents(events);
    final agentStepCount = orderedEvents.where(_isAgentProgressEvent).length;
    final latest = orderedEvents.isEmpty ? null : orderedEvents.last;
    return Column(
      crossAxisAlignment: CrossAxisAlignment.start,
      children: [
        Row(
          children: [
            Expanded(
              child: Text(
                '执行过程',
                style: Theme.of(context).textTheme.titleMedium?.copyWith(
                      fontWeight: FontWeight.w700,
                    ),
              ),
            ),
            if (latest != null)
              _StatusPill(
                icon: _eventIcon(latest),
                color: _eventColor(latest, scheme),
                label: _statusLabel(latest.status),
              ),
          ],
        ),
        const SizedBox(height: 8),
        Wrap(
          spacing: 8,
          runSpacing: 8,
          crossAxisAlignment: WrapCrossAlignment.center,
          children: [
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
              color: scheme.surface,
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
          _TimelineWindow(
            events: orderedEvents,
            hasMoreBefore: hasMoreBefore,
            loadingOlderEvents: loadingOlderEvents,
            answeringQuestionId: answeringQuestionId,
            onLoadOlderEvents: onLoadOlderEvents,
            onAnswerQuestion: onAnswerQuestion,
          ),
      ],
    );
  }
}

class _TimelineStep extends StatelessWidget {
  final DeviceTaskEvent event;
  final bool isLast;
  final Widget child;

  const _TimelineStep({
    required this.event,
    required this.isLast,
    required this.child,
  });

  @override
  Widget build(BuildContext context) {
    final color = _eventColor(event, Theme.of(context).colorScheme);
    return LayoutBuilder(
      builder: (context, constraints) {
        final timeWidth = constraints.maxWidth < 520 ? 42.0 : 56.0;
        return Row(
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            SizedBox(
              width: timeWidth,
              child: Padding(
                padding: const EdgeInsets.only(top: 7),
                child: Text(
                  _formatEventTime(event.createdAt),
                  textAlign: TextAlign.right,
                  style: Theme.of(context).textTheme.labelSmall?.copyWith(
                        color: Theme.of(context).colorScheme.onSurfaceVariant,
                      ),
                ),
              ),
            ),
            const SizedBox(width: 8),
            SizedBox(
              width: 16,
              child: Column(
                children: [
                  Container(
                    width: 10,
                    height: 10,
                    margin: const EdgeInsets.only(top: 12),
                    decoration: BoxDecoration(
                      color: color,
                      borderRadius: BorderRadius.circular(5),
                    ),
                  ),
                  if (!isLast)
                    Container(
                      width: 2,
                      height: 46,
                      margin: const EdgeInsets.symmetric(vertical: 4),
                      color: color.withValues(alpha: 0.22),
                    ),
                ],
              ),
            ),
            const SizedBox(width: 8),
            Expanded(
              child: RepaintBoundary(
                child: KeyedSubtree(
                  key: ValueKey(event.eventId),
                  child: child,
                ),
              ),
            ),
          ],
        );
      },
    );
  }
}

class _LazyExpansionTile extends StatefulWidget {
  final Widget title;
  final Widget? subtitle;
  final WidgetBuilder childrenBuilder;

  const _LazyExpansionTile({
    required this.title,
    required this.childrenBuilder,
    this.subtitle,
  });

  @override
  State<_LazyExpansionTile> createState() => _LazyExpansionTileState();
}

class _LazyExpansionTileState extends State<_LazyExpansionTile> {
  bool expanded = false;

  @override
  Widget build(BuildContext context) {
    return ExpansionTile(
      tilePadding: EdgeInsets.zero,
      childrenPadding: EdgeInsets.zero,
      title: widget.title,
      subtitle: widget.subtitle,
      onExpansionChanged: (value) {
        if (expanded != value) {
          setState(() => expanded = value);
        }
      },
      children: [
        if (expanded) widget.childrenBuilder(context),
      ],
    );
  }
}

class _TimelineWindow extends StatefulWidget {
  final List<DeviceTaskEvent> events;
  final bool hasMoreBefore;
  final bool loadingOlderEvents;
  final String answeringQuestionId;
  final VoidCallback onLoadOlderEvents;
  final Future<void> Function({
    required String questionId,
    required String answer,
    required String questionTaskId,
  }) onAnswerQuestion;

  const _TimelineWindow({
    required this.events,
    required this.hasMoreBefore,
    required this.loadingOlderEvents,
    required this.answeringQuestionId,
    required this.onLoadOlderEvents,
    required this.onAnswerQuestion,
  });

  @override
  State<_TimelineWindow> createState() => _TimelineWindowState();
}

class _TimelineWindowState extends State<_TimelineWindow> {
  @override
  Widget build(BuildContext context) {
    return Column(
      crossAxisAlignment: CrossAxisAlignment.start,
      children: [
        if (widget.hasMoreBefore)
          Padding(
            padding: const EdgeInsets.only(bottom: 8),
            child: OutlinedButton.icon(
              onPressed:
                  widget.loadingOlderEvents ? null : widget.onLoadOlderEvents,
              icon: widget.loadingOlderEvents
                  ? const SizedBox(
                      width: 16,
                      height: 16,
                      child: CircularProgressIndicator(strokeWidth: 2),
                    )
                  : const Icon(Icons.unfold_more),
              label: const Text('加载更早事件'),
            ),
          ),
        ...widget.events.indexed.map(
          (entry) => _TimelineStep(
            event: entry.$2,
            isLast: entry.$1 == widget.events.length - 1,
            child: _EventTile(
              event: entry.$2,
              answeringQuestionId: widget.answeringQuestionId,
              questionAnswered: _questionAnswered(entry.$2, widget.events),
              onAnswerQuestion: widget.onAnswerQuestion,
            ),
          ),
        ),
      ],
    );
  }

  static bool _questionAnswered(
    DeviceTaskEvent event,
    List<DeviceTaskEvent> events,
  ) {
    final question = _asMap(event.payload['codexQuestion']);
    final questionId = _textValue(question['questionId']);
    if (questionId == null) return false;
    return events.any((candidate) {
      final answer = _asMap(candidate.payload['codexQuestionAnswer']);
      return _textValue(answer['questionId']) == questionId;
    });
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
    return Wrap(
      spacing: 8,
      runSpacing: 8,
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
  final String answeringQuestionId;
  final bool questionAnswered;
  final Future<void> Function({
    required String questionId,
    required String answer,
    required String questionTaskId,
  }) onAnswerQuestion;

  const _EventTile({
    required this.event,
    required this.answeringQuestionId,
    required this.questionAnswered,
    required this.onAnswerQuestion,
  });

  @override
  Widget build(BuildContext context) {
    if (_asMap(event.payload['codexQuestion']).isNotEmpty) {
      return _CodexQuestionTile(
        event: event,
        answering: answeringQuestionId ==
            _textValue(_asMap(event.payload['codexQuestion'])['questionId']),
        answered: questionAnswered,
        onAnswerQuestion: onAnswerQuestion,
      );
    }
    if (_asMap(event.payload['testerProgress']).isNotEmpty) {
      return _TesterProgressTile(event: event);
    }
    if (_isAgentProgressEvent(event)) {
      return _AgentProgressTile(event: event);
    }
    if (event.status == DeviceTaskStatus.completed) {
      return _CompletedEventTile(event: event);
    }
    return _GenericEventTile(event: event);
  }
}

class _CodexQuestionTile extends StatefulWidget {
  final DeviceTaskEvent event;
  final bool answering;
  final bool answered;
  final Future<void> Function({
    required String questionId,
    required String answer,
    required String questionTaskId,
  }) onAnswerQuestion;

  const _CodexQuestionTile({
    required this.event,
    required this.answering,
    required this.answered,
    required this.onAnswerQuestion,
  });

  @override
  State<_CodexQuestionTile> createState() => _CodexQuestionTileState();
}

class _CodexQuestionTileState extends State<_CodexQuestionTile> {
  final TextEditingController controller = TextEditingController();

  @override
  void dispose() {
    controller.dispose();
    super.dispose();
  }

  @override
  Widget build(BuildContext context) {
    final question = _asMap(widget.event.payload['codexQuestion']);
    final questionId = _textValue(question['questionId']) ?? '';
    final taskId = _textValue(question['taskId']) ?? widget.event.taskId;
    final requester = _textValue(question['requester']) ?? 'codex';
    final skillProposal = _asMap(question['skillProposal']);
    final options = (question['options'] as List<dynamic>? ?? [])
        .map((item) => item.toString())
        .where((item) => item.isNotEmpty)
        .toList();
    final sourceName = requester == 'agent-skill-proposal'
        ? 'EvoForge skill 方案'
        : requester == 'agent'
            ? 'EvoForge'
            : 'Codex';
    return _LogFrame(
      icon: requester == 'agent-skill-proposal'
          ? Icons.auto_fix_high
          : Icons.contact_support,
      color: widget.answered
          ? Colors.green
          : Theme.of(context).colorScheme.secondary,
      title: widget.answered ? '$sourceName 已回复' : '$sourceName 请求人工输入',
      subtitle: widget.event.message,
      children: [
        if (_textValue(question['question']) != null)
          _DetailLine(label: '问题', text: _textValue(question['question'])!),
        if (skillProposal.isNotEmpty)
          _SkillProposalSummary(proposal: skillProposal),
        if (_textValue(question['projectKey']) != null)
          _DetailLine(label: '项目', text: _textValue(question['projectKey'])!),
        if (options.isNotEmpty)
          Wrap(
            spacing: 8,
            runSpacing: 8,
            children: options
                .map(
                  (option) => ActionChip(
                    label: Text(option),
                    onPressed: widget.answered || widget.answering
                        ? null
                        : () => _answer(option, questionId, taskId),
                  ),
                )
                .toList(),
          ),
        if (widget.answered)
          const Padding(
            padding: EdgeInsets.only(top: 8),
            child: _StatusPill(
              icon: Icons.mark_chat_read,
              color: Colors.green,
              label: '回复已送达',
            ),
          ),
        if (!widget.answered) ...[
          const SizedBox(height: 8),
          TextField(
            controller: controller,
            minLines: 2,
            maxLines: 4,
            decoration: const InputDecoration(
              labelText: '回复',
              border: OutlineInputBorder(),
            ),
          ),
          const SizedBox(height: 8),
          Align(
            alignment: Alignment.centerRight,
            child: FilledButton.icon(
              onPressed: widget.answering
                  ? null
                  : () => _answer(controller.text, questionId, taskId),
              icon: widget.answering
                  ? const SizedBox(
                      width: 16,
                      height: 16,
                      child: CircularProgressIndicator(strokeWidth: 2),
                    )
                  : const Icon(Icons.reply),
              label: const Text('发送回复'),
            ),
          ),
        ],
        _RawEventDetails(event: widget.event),
      ],
    );
  }

  Future<void> _answer(String answer, String questionId, String taskId) {
    return widget.onAnswerQuestion(
      questionId: questionId,
      answer: answer,
      questionTaskId: taskId,
    );
  }
}

class _SkillProposalSummary extends StatelessWidget {
  final Map<String, dynamic> proposal;

  const _SkillProposalSummary({required this.proposal});

  @override
  Widget build(BuildContext context) {
    final workflow = _asStringList(proposal['workflow']);
    final inputs = _asStringList(proposal['inputs']);
    return Container(
      width: double.infinity,
      margin: const EdgeInsets.only(top: 8, bottom: 8),
      padding: const EdgeInsets.all(12),
      decoration: BoxDecoration(
        color: Theme.of(context).colorScheme.primary.withValues(alpha: 0.06),
        borderRadius: BorderRadius.circular(8),
        border: Border.all(color: Theme.of(context).dividerColor),
      ),
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          if (_textValue(proposal['name']) != null)
            _DetailLine(label: 'Skill 名称', text: _textValue(proposal['name'])!),
          if (_textValue(proposal['description']) != null)
            _DetailLine(
              label: '描述',
              text: _textValue(proposal['description'])!,
            ),
          if (_textValue(proposal['purpose']) != null)
            _DetailLine(label: '用途', text: _textValue(proposal['purpose'])!),
          if (inputs.isNotEmpty)
            _DetailLine(label: '输入', text: inputs.join('；')),
          if (_textValue(proposal['expectedOutput']) != null)
            _DetailLine(
              label: '输出',
              text: _textValue(proposal['expectedOutput'])!,
            ),
          if (workflow.isNotEmpty) ...[
            const SizedBox(height: 6),
            Text(
              '工作流',
              style: Theme.of(context).textTheme.labelMedium,
            ),
            const SizedBox(height: 4),
            ...workflow.indexed.map(
              (entry) => Padding(
                padding: const EdgeInsets.only(bottom: 2),
                child: Text('${entry.$1 + 1}. ${entry.$2}'),
              ),
            ),
          ],
        ],
      ),
    );
  }
}

class _TesterProgressTile extends StatelessWidget {
  final DeviceTaskEvent event;

  const _TesterProgressTile({required this.event});

  @override
  Widget build(BuildContext context) {
    final progress = _asMap(event.payload['testerProgress']);
    final phase = _textValue(progress['phase']) ?? 'progress';
    final failed = phase.contains('failed') || event.level == 'error';
    return _LogFrame(
      icon: failed ? Icons.error : Icons.fact_check,
      color: failed ? Colors.red : Theme.of(context).colorScheme.primary,
      title: failed ? '测试检查失败' : '测试检查',
      subtitle: event.message,
      children: [
        if (_hasValue(progress['command']))
          _DetailLine(label: '命令', text: progress['command'].toString()),
        if (_hasValue(progress['name']))
          _DetailLine(label: '检查项', text: progress['name'].toString()),
        if (_hasValue(progress['exitCode']))
          _DetailLine(label: '退出码', text: progress['exitCode'].toString()),
        if (_hasValue(progress['durationMs']))
          _DetailLine(label: '耗时', text: '${progress['durationMs']} ms'),
        if (event.output.isNotEmpty)
          _JsonBlock(title: '测试输出', value: event.output, maxLines: 12),
        _RawEventDetails(event: event),
      ],
    );
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
    final skillProposal = _asMap(payload['skillProposal']);
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
      if (skillProposal.isNotEmpty)
        _SkillProposalSummary(proposal: skillProposal),
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
    final testerRun = _asMap(event.payload['testerRun']);
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
        if (testerRun.isNotEmpty) _TesterRunSummary(testerRun: testerRun),
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
    final testerRun = _asMap(event.payload['testerRun']);
    return _LogFrame(
      icon: _eventIcon(event),
      color: color,
      title: _eventTitle(event),
      subtitle: event.message.isEmpty ? event.createdAt : event.message,
      children: [
        if (event.output.isNotEmpty)
          _JsonBlock(title: '输出', value: event.output),
        if (testerRun.isNotEmpty) _TesterRunSummary(testerRun: testerRun),
        if (event.payload.isNotEmpty) _RawEventDetails(event: event),
      ],
    );
  }
}

class _TesterRunSummary extends StatelessWidget {
  final Map<String, dynamic> testerRun;

  const _TesterRunSummary({required this.testerRun});

  @override
  Widget build(BuildContext context) {
    final evidence = _asMap(testerRun['evidenceSummary']);
    final risk = _asMap(testerRun['riskProfile']);
    final failedIds = (evidence['failedCapabilityIds'] as List<dynamic>? ?? [])
        .map((item) => item.toString())
        .toList();
    final repairPrompt = _textValue(testerRun['repairPrompt']) ??
        _textValue(evidence['repairPrompt']);
    return Column(
      crossAxisAlignment: CrossAxisAlignment.start,
      children: [
        Wrap(
          spacing: 8,
          runSpacing: 8,
          children: [
            _StatusPill(
              icon: testerRun['passed'] == true
                  ? Icons.verified
                  : Icons.report_problem,
              color: testerRun['passed'] == true ? Colors.green : Colors.red,
              label: testerRun['passed'] == true ? '测试通过' : '需要修复',
            ),
            if (_textValue(testerRun['routeId']) != null)
              _StatusPill(
                icon: Icons.alt_route,
                color: Colors.blueGrey,
                label: _textValue(testerRun['routeId'])!,
              ),
            if (_textValue(risk['level']) != null)
              _StatusPill(
                icon: Icons.radar,
                color: Theme.of(context).colorScheme.primary,
                label: '风险 ${_textValue(risk['level'])}',
              ),
            if (failedIds.isNotEmpty)
              _StatusPill(
                icon: Icons.bug_report,
                color: Colors.red,
                label: '失败 ${failedIds.length}',
              ),
          ],
        ),
        if (failedIds.isNotEmpty)
          Padding(
            padding: const EdgeInsets.only(top: 8),
            child: _DetailLine(label: '失败能力', text: failedIds.join(', ')),
          ),
        if (repairPrompt != null)
          Padding(
            padding: const EdgeInsets.only(top: 8),
            child: _JsonBlock(
              title: '修复提示',
              value: repairPrompt,
              maxLines: 12,
            ),
          ),
        const SizedBox(height: 8),
        _JsonBlock(title: 'EvoForge 测试报告', value: testerRun, maxLines: 18),
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
    final tonedBackground = color.withValues(alpha: 0.035);
    return Padding(
      padding: const EdgeInsets.only(bottom: 8),
      child: Container(
        width: double.infinity,
        padding: const EdgeInsets.all(12),
        decoration: BoxDecoration(
          color: tonedBackground,
          border: Border.all(color: color.withValues(alpha: 0.22)),
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

class _JsonBlock extends StatefulWidget {
  final String title;
  final Object? value;
  final int maxLines;

  const _JsonBlock({
    required this.title,
    required this.value,
    this.maxLines = 16,
  });

  @override
  State<_JsonBlock> createState() => _JsonBlockState();
}

class _JsonBlockState extends State<_JsonBlock> {
  late String pretty;

  @override
  void initState() {
    super.initState();
    pretty = _prettyJson(widget.value);
  }

  @override
  void didUpdateWidget(covariant _JsonBlock oldWidget) {
    super.didUpdateWidget(oldWidget);
    if (!identical(oldWidget.value, widget.value) ||
        oldWidget.value != widget.value) {
      pretty = _prettyJson(widget.value);
    }
  }

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
          Text(widget.title, style: Theme.of(context).textTheme.labelMedium),
          const SizedBox(height: 6),
          SelectableText(
            pretty,
            maxLines: widget.maxLines,
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
    return _LazyExpansionTile(
      title: Text(
        '原始事件',
        style: Theme.of(context).textTheme.labelMedium,
      ),
      childrenBuilder: (_) => _JsonBlock(
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
    return _LazyExpansionTile(
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
      childrenBuilder: (_) => Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          if (routes.isNotEmpty) _RoutesView(routes: routes),
          if (observations.isNotEmpty)
            _JsonBlock(title: '观察记录', value: observations, maxLines: 24),
        ],
      ),
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
    DeviceTaskStatus.needsInput => '等待人工回复',
    DeviceTaskStatus.inputReceived => '人工回复',
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
    DeviceTaskStatus.needsInput => Icons.contact_support,
    DeviceTaskStatus.inputReceived => Icons.mark_chat_read,
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

List<String> _asStringList(Object? value) {
  if (value is Iterable) {
    return value
        .map((item) => item.toString().trim())
        .where((item) => item.isNotEmpty)
        .toList(growable: false);
  }
  final text = _textValue(value);
  return text == null ? const [] : [text];
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
