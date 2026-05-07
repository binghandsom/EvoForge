import 'package:flutter/material.dart';

import '../../shared/api/evoforge_api.dart';
import '../../shared/models/device_status.dart';
import '../../shared/widgets/page_header.dart';

class DevicesPage extends StatefulWidget {
  final EvoForgeApi api;

  const DevicesPage({super.key, required this.api});

  @override
  State<DevicesPage> createState() => _DevicesPageState();
}

class _DevicesPageState extends State<DevicesPage> {
  DeviceStatus? status;
  bool loading = false;

  @override
  void initState() {
    super.initState();
    load();
  }

  Future<void> load() async {
    setState(() => loading = true);
    try {
      final data = await widget.api.loadDeviceStatus();
      setState(() => status = data);
    } catch (e) {
      showMessage('加载失败: $e');
    } finally {
      if (mounted) setState(() => loading = false);
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
    final current = status;
    return Column(
      children: [
        PageHeader(
          title: '设备',
          subtitle: '本机 Agent 状态',
          actions: [
            IconButton(
              onPressed: loading ? null : load,
              icon: const Icon(Icons.refresh),
              tooltip: '刷新',
            ),
          ],
        ),
        if (loading) const LinearProgressIndicator(),
        Expanded(
          child: current == null
              ? const Center(child: Text('暂无设备状态'))
              : ListView(
                  padding: const EdgeInsets.fromLTRB(24, 4, 24, 24),
                  children: [
                    _StatusTile(status: current),
                    const SizedBox(height: 12),
                    _InfoGrid(status: current),
                    const SizedBox(height: 12),
                    _CodexTaskTile(status: current),
                    const SizedBox(height: 12),
                    Wrap(
                      spacing: 8,
                      runSpacing: 8,
                      children: current.capabilities
                          .map((item) => Chip(label: Text(item)))
                          .toList(),
                    ),
                    const SizedBox(height: 8),
                    Wrap(
                      spacing: 8,
                      runSpacing: 8,
                      children: current.commandTypes
                          .map(
                            (item) => Chip(
                              avatar: const Icon(Icons.input, size: 16),
                              label: Text(item),
                            ),
                          )
                          .toList(),
                    ),
                  ],
                ),
        ),
      ],
    );
  }
}

class _CodexTaskTile extends StatelessWidget {
  final DeviceStatus status;

  const _CodexTaskTile({required this.status});

  @override
  Widget build(BuildContext context) {
    final codex = status.codexTask;
    final color = codex.enabled ? Colors.green : Colors.grey;
    return ListTile(
      contentPadding: const EdgeInsets.symmetric(horizontal: 12, vertical: 8),
      leading: Icon(Icons.code, color: color),
      title: const Text('Codex 任务执行'),
      subtitle: SelectableText(
        [
          '默认 ${codex.defaultWorkspace.isEmpty ? (codex.workingDirectory.isEmpty ? '.' : codex.workingDirectory) : codex.defaultWorkspace}',
          '超时 ${codex.timeoutSeconds}s',
          if (codex.workspaces.isNotEmpty)
            '项目 ${codex.workspaces.map((item) => '${item.key}: ${item.path}').join(' · ')}',
        ].join(' · '),
      ),
      trailing: Chip(
        avatar: Icon(
          codex.enabled ? Icons.check_circle : Icons.lock,
          size: 18,
        ),
        label: Text(codex.enabled ? '已启用' : '默认关闭'),
      ),
      shape: RoundedRectangleBorder(
        side: BorderSide(color: Theme.of(context).dividerColor),
        borderRadius: BorderRadius.circular(8),
      ),
    );
  }
}

class _StatusTile extends StatelessWidget {
  final DeviceStatus status;

  const _StatusTile({required this.status});

  @override
  Widget build(BuildContext context) {
    final color = status.enabled ? Colors.green : Colors.grey;
    return ListTile(
      contentPadding: const EdgeInsets.symmetric(horizontal: 12, vertical: 8),
      leading: Icon(Icons.computer, color: color),
      title: Text(status.deviceId.isEmpty ? 'local-pc' : status.deviceId),
      subtitle: Text(status.userId),
      trailing: Chip(
        avatar: Icon(
          status.enabled ? Icons.check_circle : Icons.pause_circle,
          size: 18,
        ),
        label: Text(status.enabled ? '在线' : '未启用'),
      ),
      shape: RoundedRectangleBorder(
        side: BorderSide(color: Theme.of(context).dividerColor),
        borderRadius: BorderRadius.circular(8),
      ),
    );
  }
}

class _InfoGrid extends StatelessWidget {
  final DeviceStatus status;

  const _InfoGrid({required this.status});

  @override
  Widget build(BuildContext context) {
    final items = {
      'Command Exchange': status.commandExchange,
      'Event Exchange': status.eventExchange,
      'Command Queue': status.commandQueue,
      'Event Queue': status.eventQueue,
      'Command Routing Key': status.commandRoutingKey,
      'Event Routing Key': status.eventRoutingKey,
      'Command Signing': status.commandSigningEnabled
          ? 'enabled · ${status.commandSigningTtlSeconds}s'
          : 'disabled',
      'Replay Protection': status.persistentReplayProtection
          ? '${status.commandReplayStore} · persistent'
          : '${status.commandReplayStore} · process memory',
      'Event Signing': status.eventSigningEnabled ? 'enabled' : 'disabled',
      'Heartbeat': '${status.heartbeatSeconds}s',
    };
    return Wrap(
      spacing: 12,
      runSpacing: 12,
      children: items.entries
          .map(
            (entry) => SizedBox(
              width: 360,
              child: ListTile(
                title: Text(entry.key),
                subtitle: SelectableText(entry.value),
                shape: RoundedRectangleBorder(
                  side: BorderSide(color: Theme.of(context).dividerColor),
                  borderRadius: BorderRadius.circular(8),
                ),
              ),
            ),
          )
          .toList(),
    );
  }
}
