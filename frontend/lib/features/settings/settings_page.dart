import 'package:flutter/material.dart';

import '../../shared/api/evoforge_api.dart';
import '../../shared/config/frontend_runtime_config.dart';
import '../../shared/messaging/device_mobile_connection_config.dart';
import '../../shared/models/device_status.dart';
import '../../shared/models/model_config.dart';
import '../../shared/widgets/page_header.dart';

class SettingsPage extends StatefulWidget {
  final EvoForgeApi api;

  const SettingsPage({super.key, required this.api});

  @override
  State<SettingsPage> createState() => _SettingsPageState();
}

class _SettingsPageState extends State<SettingsPage> {
  DeviceStatus? status;
  FrontendRuntimeConfig runtimeConfig = FrontendRuntimeConfig.empty();
  List<ModelProviderConfigView> modelConfigs = const [];
  List<TesterCommandStatus> testerCapabilities = const [];
  String selectedTesterProjectKey = '';
  String runtimeConfigError = '';
  bool loading = false;
  bool configLoading = false;
  bool modelConfigLoading = false;
  bool testerLoading = false;

  @override
  void initState() {
    super.initState();
    loadAll();
  }

  Future<void> loadAll() async {
    await Future.wait([loadStatus(), loadRuntimeConfig(), loadModelConfigs()]);
    await loadTesterCapabilities();
  }

  Future<void> loadStatus() async {
    setState(() => loading = true);
    try {
      final data = await widget.api.loadDeviceStatus();
      if (mounted) {
        setState(() {
          status = data;
          selectedTesterProjectKey = _resolveTesterProjectKey(
            data,
            selectedTesterProjectKey,
          );
        });
      }
    } catch (e) {
      showMessage('加载设备配置失败: $e');
    } finally {
      if (mounted) setState(() => loading = false);
    }
  }

  Future<void> loadRuntimeConfig() async {
    setState(() {
      configLoading = true;
      runtimeConfigError = '';
    });
    try {
      final config = await FrontendRuntimeConfig.load();
      if (mounted) setState(() => runtimeConfig = config);
    } catch (e) {
      if (mounted) setState(() => runtimeConfigError = e.toString());
    } finally {
      if (mounted) setState(() => configLoading = false);
    }
  }

  Future<void> loadModelConfigs() async {
    setState(() => modelConfigLoading = true);
    try {
      final configs = await widget.api.loadModelConfigs();
      if (mounted) setState(() => modelConfigs = configs);
    } catch (e) {
      showMessage('加载模型配置失败: $e');
    } finally {
      if (mounted) setState(() => modelConfigLoading = false);
    }
  }

  Future<void> loadTesterCapabilities([String? projectKey]) async {
    final key = projectKey ?? selectedTesterProjectKey;
    if (key.isEmpty || testerLoading) return;
    setState(() => testerLoading = true);
    try {
      final capabilities = await widget.api.loadTesterCapabilities(key);
      if (mounted) setState(() => testerCapabilities = capabilities);
    } catch (e) {
      showMessage('加载测试员能力失败: $e');
    } finally {
      if (mounted) setState(() => testerLoading = false);
    }
  }

  Future<void> discoverTesterCapabilities({required bool useModel}) async {
    final key = selectedTesterProjectKey;
    if (key.isEmpty || testerLoading) return;
    setState(() => testerLoading = true);
    try {
      final capabilities = await widget.api.discoverTesterCapabilities(
        projectKey: key,
        useModel: useModel,
      );
      if (mounted) setState(() => testerCapabilities = capabilities);
      showMessage(useModel ? '模型已更新测试员能力' : '已根据项目模板发现测试员能力');
    } catch (e) {
      showMessage('发现测试员能力失败: $e');
    } finally {
      if (mounted) setState(() => testerLoading = false);
    }
  }

  Future<void> openTesterCapabilityDialog([
    TesterCommandStatus? capability,
  ]) async {
    final payload = await showDialog<Map<String, Object?>>(
      context: context,
      builder: (context) => _TesterCapabilityDialog(
        projectKey: selectedTesterProjectKey,
        capability: capability,
      ),
    );
    if (payload == null) return;
    try {
      await widget.api.saveTesterCapability(payload);
      showMessage(capability == null ? '测试员能力已创建' : '测试员能力已更新');
      await loadTesterCapabilities();
    } catch (e) {
      showMessage('保存测试员能力失败: $e');
    }
  }

  Future<void> deleteTesterCapability(TesterCommandStatus capability) async {
    final confirmed = await showDialog<bool>(
      context: context,
      builder: (context) => AlertDialog(
        title: const Text('删除测试员能力'),
        content: Text('确定删除 ${capability.name}？之后测试计划不会再选择它。'),
        actions: [
          TextButton(
            onPressed: () => Navigator.of(context).pop(false),
            child: const Text('取消'),
          ),
          FilledButton(
            onPressed: () => Navigator.of(context).pop(true),
            child: const Text('删除'),
          ),
        ],
      ),
    );
    if (confirmed != true) return;
    try {
      await widget.api.deleteTesterCapability(
        projectKey: selectedTesterProjectKey,
        id: capability.id,
      );
      showMessage('测试员能力已删除');
      await loadTesterCapabilities();
    } catch (e) {
      showMessage('删除测试员能力失败: $e');
    }
  }

  void changeTesterProject(String projectKey) {
    setState(() {
      selectedTesterProjectKey = projectKey;
      testerCapabilities = const [];
    });
    loadTesterCapabilities(projectKey);
  }

  Future<void> openModelConfigDialog([ModelProviderConfigView? config]) async {
    final payload = await showDialog<Map<String, Object?>>(
      context: context,
      builder: (context) => _ModelProviderDialog(config: config),
    );
    if (payload == null) return;

    try {
      if (config == null) {
        await widget.api.createModelConfig(payload);
      } else {
        await widget.api.updateModelConfig(config.id, payload);
      }
      showMessage(config == null ? '模型配置已创建' : '模型配置已更新');
      await loadModelConfigs();
    } catch (e) {
      showMessage('保存模型配置失败: $e');
    }
  }

  Future<void> deleteModelConfig(ModelProviderConfigView config) async {
    final confirmed = await showDialog<bool>(
      context: context,
      builder: (context) => AlertDialog(
        title: const Text('删除模型配置'),
        content: Text('确定删除 ${config.name}？已保存的 API Key 也会一起移除。'),
        actions: [
          TextButton(
            onPressed: () => Navigator.of(context).pop(false),
            child: const Text('取消'),
          ),
          FilledButton(
            onPressed: () => Navigator.of(context).pop(true),
            child: const Text('删除'),
          ),
        ],
      ),
    );
    if (confirmed != true) return;

    try {
      await widget.api.deleteModelConfig(config.id);
      showMessage('模型配置已删除');
      await loadModelConfigs();
    } catch (e) {
      showMessage('删除模型配置失败: $e');
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
          title: '设置',
          subtitle: '运行配置',
          actions: [
            IconButton(
              onPressed: loading || configLoading ? null : loadAll,
              icon: const Icon(Icons.refresh),
              tooltip: '刷新设备配置',
            ),
          ],
        ),
        if (loading || configLoading || modelConfigLoading || testerLoading)
          const LinearProgressIndicator(),
        Expanded(
          child: ListView(
            children: [
              Padding(
                padding: const EdgeInsets.fromLTRB(24, 4, 24, 12),
                child: _SettingsBody(
                  usesMessageBus: widget.api.usesMessageBus,
                  runtimeConfig: runtimeConfig,
                  runtimeConfigError: runtimeConfigError,
                ),
              ),
              Padding(
                padding: const EdgeInsets.fromLTRB(24, 0, 24, 24),
                child: _ModelConfigSection(
                  configs: modelConfigs,
                  loading: modelConfigLoading,
                  onAdd: () => openModelConfigDialog(),
                  onEdit: openModelConfigDialog,
                  onDelete: deleteModelConfig,
                ),
              ),
              Padding(
                padding: const EdgeInsets.fromLTRB(24, 0, 24, 24),
                child: current == null
                    ? const _TesterCapabilityPlaceholder()
                    : _TesterCapabilitySection(
                        status: current,
                        projectKey: selectedTesterProjectKey,
                        capabilities: testerCapabilities,
                        loading: testerLoading,
                        onProjectChanged: changeTesterProject,
                        onRefresh: loadTesterCapabilities,
                        onDiscover: () =>
                            discoverTesterCapabilities(useModel: false),
                        onModelDiscover: () =>
                            discoverTesterCapabilities(useModel: true),
                        onAdd: () => openTesterCapabilityDialog(),
                        onEdit: openTesterCapabilityDialog,
                        onDelete: deleteTesterCapability,
                      ),
              ),
              Padding(
                padding: const EdgeInsets.fromLTRB(24, 0, 24, 24),
                child: current == null
                    ? const _MobileConnectionPlaceholder()
                    : _MobileConnectionSection(
                        status: current,
                        runtimeConfig: runtimeConfig,
                        runtimeConfigError: runtimeConfigError,
                      ),
              ),
            ],
          ),
        ),
      ],
    );
  }
}

class _SettingsBody extends StatelessWidget {
  final bool usesMessageBus;
  final FrontendRuntimeConfig runtimeConfig;
  final String runtimeConfigError;

  const _SettingsBody({
    required this.usesMessageBus,
    required this.runtimeConfig,
    required this.runtimeConfigError,
  });

  @override
  Widget build(BuildContext context) {
    final configStatus = runtimeConfigError.isNotEmpty
        ? '加载失败'
        : runtimeConfig.fallbackUsed
            ? '${runtimeConfig.sourceAsset} (fallback)'
            : runtimeConfig.sourceAsset;
    return Wrap(
      spacing: 12,
      runSpacing: 12,
      children: [
        _SettingTile(
          label: '数据通道',
          value: usesMessageBus ? '消息总线' : 'REST 诊断通道',
        ),
        _SettingTile(label: 'Frontend Config', value: configStatus),
        const _SettingTile(
          label: 'Remote Agent',
          value: 'EVOFORGE_DEVICE_AGENT_ENABLED',
        ),
        const _SettingTile(
          label: 'RabbitMQ Host',
          value: 'EVOFORGE_RABBITMQ_HOST',
        ),
        const _SettingTile(label: 'Event Queue', value: 'EVOFORGE_EVENT_QUEUE'),
        const _SettingTile(
          label: 'Command Signing',
          value: 'EVOFORGE_COMMAND_SIGNING_SECRET',
        ),
        const _SettingTile(
          label: 'Signature TTL',
          value: 'EVOFORGE_COMMAND_SIGNATURE_TTL_SECONDS',
        ),
        const _SettingTile(
          label: 'Storage Backend',
          value: 'EVOFORGE_SKILL_STORAGE_BACKEND',
        ),
        const _SettingTile(label: 'PostgreSQL URL', value: 'EVOFORGE_DB_URL'),
        const _SettingTile(
          label: 'Codex Task',
          value: 'EVOFORGE_CODEX_TASK_ENABLED',
        ),
        const _SettingTile(
            label: 'Codex Workdir', value: 'EVOFORGE_CODEX_WORKDIR'),
      ],
    );
  }
}

String _resolveTesterProjectKey(DeviceStatus status, String current) {
  final keys = status.codexTask.workspaces.map((item) => item.key).toList();
  if (keys.contains(current)) return current;
  if (keys.contains(status.codexTask.defaultWorkspace)) {
    return status.codexTask.defaultWorkspace;
  }
  return keys.isEmpty ? status.codexTask.defaultWorkspace : keys.first;
}

class _TesterCapabilityPlaceholder extends StatelessWidget {
  const _TesterCapabilityPlaceholder();

  @override
  Widget build(BuildContext context) {
    return ListTile(
      leading: const Icon(Icons.fact_check),
      title: const Text('测试员能力库'),
      subtitle: const Text('等待设备状态'),
      shape: RoundedRectangleBorder(
        side: BorderSide(color: Theme.of(context).dividerColor),
        borderRadius: BorderRadius.circular(8),
      ),
    );
  }
}

class _TesterCapabilitySection extends StatelessWidget {
  final DeviceStatus status;
  final String projectKey;
  final List<TesterCommandStatus> capabilities;
  final bool loading;
  final ValueChanged<String> onProjectChanged;
  final Future<void> Function() onRefresh;
  final VoidCallback onDiscover;
  final VoidCallback onModelDiscover;
  final VoidCallback onAdd;
  final ValueChanged<TesterCommandStatus> onEdit;
  final ValueChanged<TesterCommandStatus> onDelete;

  const _TesterCapabilitySection({
    required this.status,
    required this.projectKey,
    required this.capabilities,
    required this.loading,
    required this.onProjectChanged,
    required this.onRefresh,
    required this.onDiscover,
    required this.onModelDiscover,
    required this.onAdd,
    required this.onEdit,
    required this.onDelete,
  });

  @override
  Widget build(BuildContext context) {
    final workspaces = status.codexTask.workspaces;
    final selected = workspaces.any((item) => item.key == projectKey)
        ? projectKey
        : workspaces.isEmpty
            ? projectKey
            : workspaces.first.key;
    return Column(
      crossAxisAlignment: CrossAxisAlignment.start,
      children: [
        Row(
          children: [
            Expanded(
              child: Text(
                '测试员能力库',
                style: Theme.of(context).textTheme.titleMedium,
              ),
            ),
            IconButton(
              onPressed: loading ? null : onRefresh,
              icon: const Icon(Icons.refresh),
              tooltip: '刷新',
            ),
            FilledButton.icon(
              onPressed: loading || selected.isEmpty ? null : onDiscover,
              icon: const Icon(Icons.auto_fix_high),
              label: const Text('自动发现'),
            ),
            const SizedBox(width: 8),
            OutlinedButton.icon(
              onPressed: loading || selected.isEmpty ? null : onModelDiscover,
              icon: const Icon(Icons.psychology),
              label: const Text('模型优化'),
            ),
            const SizedBox(width: 8),
            OutlinedButton.icon(
              onPressed: selected.isEmpty ? null : onAdd,
              icon: const Icon(Icons.add),
              label: const Text('新增'),
            ),
          ],
        ),
        const SizedBox(height: 8),
        Wrap(
          spacing: 10,
          runSpacing: 8,
          crossAxisAlignment: WrapCrossAlignment.center,
          children: [
            ConstrainedBox(
              constraints: const BoxConstraints(minWidth: 220, maxWidth: 320),
              child: DropdownButtonFormField<String>(
                initialValue: selected.isEmpty ? null : selected,
                decoration: const InputDecoration(
                  labelText: '项目',
                  border: OutlineInputBorder(),
                  prefixIcon: Icon(Icons.folder_open),
                ),
                items: workspaces
                    .map(
                      (workspace) => DropdownMenuItem(
                        value: workspace.key,
                        child: Text(workspace.key),
                      ),
                    )
                    .toList(),
                onChanged: (value) {
                  if (value != null) onProjectChanged(value);
                },
              ),
            ),
            Chip(label: Text(status.tester.commandSource)),
            Chip(
                label: Text(
                    status.tester.autoDiscoverEnabled ? '自动发现开启' : '自动发现关闭')),
            Chip(
                label: Text(
                    status.tester.autoOptimizeEnabled ? '运行时优化开启' : '运行时优化关闭')),
            if (status.tester.modelDiscoveryEnabled)
              const Chip(label: Text('模型发现默认开启')),
          ],
        ),
        const SizedBox(height: 10),
        if (capabilities.isEmpty)
          ListTile(
            leading: const Icon(Icons.rule_folder),
            title: const Text('还没有测试员能力记录'),
            subtitle: const Text('点击自动发现，EvoForge 会根据项目文件和测试模板生成能力并入库。'),
            shape: RoundedRectangleBorder(
              side: BorderSide(color: Theme.of(context).dividerColor),
              borderRadius: BorderRadius.circular(8),
            ),
          )
        else
          Wrap(
            spacing: 12,
            runSpacing: 12,
            children: capabilities
                .map(
                  (capability) => _TesterCapabilityTile(
                    capability: capability,
                    onEdit: () => onEdit(capability),
                    onDelete: () => onDelete(capability),
                  ),
                )
                .toList(),
          ),
      ],
    );
  }
}

class _TesterCapabilityTile extends StatelessWidget {
  final TesterCommandStatus capability;
  final VoidCallback onEdit;
  final VoidCallback onDelete;

  const _TesterCapabilityTile({
    required this.capability,
    required this.onEdit,
    required this.onDelete,
  });

  @override
  Widget build(BuildContext context) {
    return SizedBox(
      width: 430,
      child: Card(
        margin: EdgeInsets.zero,
        shape: RoundedRectangleBorder(borderRadius: BorderRadius.circular(8)),
        child: Padding(
          padding: const EdgeInsets.all(12),
          child: Column(
            crossAxisAlignment: CrossAxisAlignment.start,
            children: [
              Row(
                crossAxisAlignment: CrossAxisAlignment.start,
                children: [
                  Expanded(
                    child: Column(
                      crossAxisAlignment: CrossAxisAlignment.start,
                      children: [
                        Text(
                          capability.name,
                          style: Theme.of(context).textTheme.titleSmall,
                        ),
                        const SizedBox(height: 2),
                        SelectableText(
                          capability.id,
                          style: Theme.of(context).textTheme.bodySmall,
                        ),
                      ],
                    ),
                  ),
                  IconButton(
                    onPressed: onEdit,
                    icon: const Icon(Icons.edit),
                    tooltip: '编辑',
                  ),
                  IconButton(
                    onPressed: onDelete,
                    icon: const Icon(Icons.delete_outline),
                    tooltip: '删除',
                  ),
                ],
              ),
              const SizedBox(height: 8),
              Wrap(
                spacing: 6,
                runSpacing: 6,
                children: [
                  Chip(label: Text(capability.enabled ? '已启用' : '已停用')),
                  if (capability.type.isNotEmpty)
                    Chip(label: Text(capability.type)),
                  if (capability.cost.isNotEmpty)
                    Chip(label: Text('成本 ${capability.cost}')),
                  if (capability.confidence.isNotEmpty)
                    Chip(label: Text('信心 ${capability.confidence}')),
                  if (capability.source.isNotEmpty)
                    Chip(label: Text(capability.source)),
                  if (capability.lastStatus.isNotEmpty)
                    Chip(label: Text('最近 ${capability.lastStatus}')),
                ],
              ),
              const SizedBox(height: 8),
              _ModelField(
                  label: '目录',
                  value: capability.workingDirectory.isEmpty
                      ? '.'
                      : capability.workingDirectory),
              _ModelField(label: '命令', value: capability.command),
              if (capability.reason.isNotEmpty)
                _ModelField(label: '原因', value: capability.reason),
              if (capability.optimizationNotes.isNotEmpty)
                _ModelField(label: '优化', value: capability.optimizationNotes),
              _ModelField(
                label: '统计',
                value:
                    '通过 ${capability.successCount} / 失败 ${capability.failureCount}',
              ),
            ],
          ),
        ),
      ),
    );
  }
}

class _TesterCapabilityDialog extends StatefulWidget {
  final String projectKey;
  final TesterCommandStatus? capability;

  const _TesterCapabilityDialog({
    required this.projectKey,
    this.capability,
  });

  @override
  State<_TesterCapabilityDialog> createState() =>
      _TesterCapabilityDialogState();
}

class _TesterCapabilityDialogState extends State<_TesterCapabilityDialog> {
  final _formKey = GlobalKey<FormState>();
  late final TextEditingController _idController;
  late final TextEditingController _nameController;
  late final TextEditingController _typeController;
  late final TextEditingController _workingDirectoryController;
  late final TextEditingController _commandController;
  late final TextEditingController _reasonController;
  late final TextEditingController _coversController;
  late final TextEditingController _tagsController;
  late final TextEditingController _evidenceParserController;
  late final TextEditingController _timeoutController;
  late bool _enabled;
  late String _cost;
  late String _confidence;

  @override
  void initState() {
    super.initState();
    final capability = widget.capability;
    _idController = TextEditingController(text: capability?.id ?? '');
    _nameController = TextEditingController(text: capability?.name ?? '');
    _typeController = TextEditingController(text: capability?.type ?? '');
    _workingDirectoryController =
        TextEditingController(text: capability?.workingDirectory ?? '.');
    _commandController = TextEditingController(text: capability?.command ?? '');
    _reasonController = TextEditingController(text: capability?.reason ?? '');
    _coversController =
        TextEditingController(text: capability?.covers.join(', ') ?? '');
    _tagsController =
        TextEditingController(text: capability?.tags.join(', ') ?? '');
    _evidenceParserController =
        TextEditingController(text: capability?.evidenceParser ?? '');
    _timeoutController = TextEditingController(
      text:
          capability?.timeoutSeconds == null || capability!.timeoutSeconds == 0
              ? ''
              : capability.timeoutSeconds.toString(),
    );
    _enabled = capability?.enabled ?? true;
    _cost = _choiceOrDefault(capability?.cost, 'medium');
    _confidence = _choiceOrDefault(capability?.confidence, 'medium');
  }

  @override
  void dispose() {
    _idController.dispose();
    _nameController.dispose();
    _typeController.dispose();
    _workingDirectoryController.dispose();
    _commandController.dispose();
    _reasonController.dispose();
    _coversController.dispose();
    _tagsController.dispose();
    _evidenceParserController.dispose();
    _timeoutController.dispose();
    super.dispose();
  }

  @override
  Widget build(BuildContext context) {
    final editing = widget.capability != null;
    return AlertDialog(
      title: Text(editing ? '编辑测试员能力' : '新增测试员能力'),
      content: SizedBox(
        width: 560,
        child: Form(
          key: _formKey,
          child: SingleChildScrollView(
            child: Column(
              mainAxisSize: MainAxisSize.min,
              children: [
                TextFormField(
                  controller: _idController,
                  decoration: const InputDecoration(labelText: '能力 ID'),
                  validator: _required('请填写能力 ID'),
                ),
                const SizedBox(height: 8),
                TextFormField(
                  controller: _nameController,
                  decoration: const InputDecoration(labelText: '名称'),
                  validator: _required('请填写名称'),
                ),
                const SizedBox(height: 8),
                TextFormField(
                  controller: _commandController,
                  decoration: const InputDecoration(
                    labelText: '命令',
                    hintText: '例如 mvn test、flutter test',
                  ),
                  validator: _required('请填写命令'),
                ),
                const SizedBox(height: 8),
                TextFormField(
                  controller: _workingDirectoryController,
                  decoration: const InputDecoration(
                    labelText: '工作目录',
                    hintText: '相对项目根目录，例如 backend、frontend、.',
                  ),
                ),
                const SizedBox(height: 8),
                TextFormField(
                  controller: _typeController,
                  decoration: const InputDecoration(labelText: '类型'),
                ),
                const SizedBox(height: 8),
                TextFormField(
                  controller: _coversController,
                  decoration: const InputDecoration(
                    labelText: '覆盖范围',
                    hintText: 'frontend, backend, message-bus',
                  ),
                ),
                const SizedBox(height: 8),
                TextFormField(
                  controller: _tagsController,
                  decoration: const InputDecoration(labelText: '标签'),
                ),
                const SizedBox(height: 8),
                Row(
                  children: [
                    Expanded(
                      child: DropdownButtonFormField<String>(
                        initialValue: _cost,
                        decoration: const InputDecoration(labelText: '成本'),
                        items: const [
                          DropdownMenuItem(value: 'low', child: Text('low')),
                          DropdownMenuItem(
                            value: 'medium',
                            child: Text('medium'),
                          ),
                          DropdownMenuItem(value: 'high', child: Text('high')),
                        ],
                        onChanged: (value) {
                          if (value != null) setState(() => _cost = value);
                        },
                      ),
                    ),
                    const SizedBox(width: 12),
                    Expanded(
                      child: DropdownButtonFormField<String>(
                        initialValue: _confidence,
                        decoration: const InputDecoration(labelText: '信心'),
                        items: const [
                          DropdownMenuItem(value: 'low', child: Text('low')),
                          DropdownMenuItem(
                            value: 'medium',
                            child: Text('medium'),
                          ),
                          DropdownMenuItem(value: 'high', child: Text('high')),
                        ],
                        onChanged: (value) {
                          if (value != null) {
                            setState(() => _confidence = value);
                          }
                        },
                      ),
                    ),
                  ],
                ),
                const SizedBox(height: 8),
                TextFormField(
                  controller: _evidenceParserController,
                  decoration: const InputDecoration(labelText: '证据解析器'),
                ),
                const SizedBox(height: 8),
                TextFormField(
                  controller: _timeoutController,
                  decoration: const InputDecoration(labelText: '超时秒数'),
                  keyboardType: TextInputType.number,
                ),
                const SizedBox(height: 8),
                TextFormField(
                  controller: _reasonController,
                  decoration: const InputDecoration(labelText: '使用原因'),
                  minLines: 2,
                  maxLines: 4,
                ),
                SwitchListTile(
                  contentPadding: EdgeInsets.zero,
                  value: _enabled,
                  onChanged: (value) => setState(() => _enabled = value),
                  title: const Text('启用'),
                ),
              ],
            ),
          ),
        ),
      ),
      actions: [
        TextButton(
          onPressed: () => Navigator.of(context).pop(),
          child: const Text('取消'),
        ),
        FilledButton(
          onPressed: _submit,
          child: const Text('保存'),
        ),
      ],
    );
  }

  FormFieldValidator<String> _required(String message) {
    return (value) => value == null || value.trim().isEmpty ? message : null;
  }

  void _submit() {
    if (!_formKey.currentState!.validate()) return;
    final payload = <String, Object?>{
      'projectKey': widget.projectKey,
      'id': _idController.text.trim(),
      'name': _nameController.text.trim(),
      'type': _typeController.text.trim(),
      'workingDirectory': _workingDirectoryController.text.trim(),
      'command': _commandController.text.trim(),
      'enabled': _enabled,
      'timeoutSeconds': int.tryParse(_timeoutController.text.trim().isEmpty
              ? '0'
              : _timeoutController.text.trim()) ??
          0,
      'reason': _reasonController.text.trim(),
      'covers': _csv(_coversController.text),
      'tags': _csv(_tagsController.text),
      'cost': _cost,
      'confidence': _confidence,
      'evidenceParser': _evidenceParserController.text.trim(),
      'source': 'manual',
    };
    Navigator.of(context).pop(payload);
  }

  static String _choiceOrDefault(String? value, String fallback) {
    const choices = {'low', 'medium', 'high'};
    return choices.contains(value) ? value! : fallback;
  }

  static List<String> _csv(String value) {
    return value
        .split(',')
        .map((item) => item.trim())
        .where((item) => item.isNotEmpty)
        .toList();
  }
}

class _ModelConfigSection extends StatelessWidget {
  final List<ModelProviderConfigView> configs;
  final bool loading;
  final VoidCallback onAdd;
  final ValueChanged<ModelProviderConfigView> onEdit;
  final ValueChanged<ModelProviderConfigView> onDelete;

  const _ModelConfigSection({
    required this.configs,
    required this.loading,
    required this.onAdd,
    required this.onEdit,
    required this.onDelete,
  });

  @override
  Widget build(BuildContext context) {
    return Column(
      crossAxisAlignment: CrossAxisAlignment.start,
      children: [
        Row(
          children: [
            Expanded(
              child: Text(
                '模型配置',
                style: Theme.of(context).textTheme.titleMedium,
              ),
            ),
            FilledButton.icon(
              onPressed: loading ? null : onAdd,
              icon: const Icon(Icons.add),
              label: const Text('新增模型'),
            ),
          ],
        ),
        const SizedBox(height: 8),
        if (configs.isEmpty)
          ListTile(
            leading: const Icon(Icons.key),
            title: const Text('还没有配置模型'),
            subtitle: const Text('添加 GPT、Claude 或兼容 OpenAI 协议的模型后即可在运行时选择。'),
            shape: RoundedRectangleBorder(
              side: BorderSide(color: Theme.of(context).dividerColor),
              borderRadius: BorderRadius.circular(8),
            ),
          )
        else
          Wrap(
            spacing: 12,
            runSpacing: 12,
            children: configs
                .map(
                  (config) => _ModelConfigTile(
                    config: config,
                    onEdit: () => onEdit(config),
                    onDelete: () => onDelete(config),
                  ),
                )
                .toList(),
          ),
      ],
    );
  }
}

class _ModelConfigTile extends StatelessWidget {
  final ModelProviderConfigView config;
  final VoidCallback onEdit;
  final VoidCallback onDelete;

  const _ModelConfigTile({
    required this.config,
    required this.onEdit,
    required this.onDelete,
  });

  @override
  Widget build(BuildContext context) {
    final textTheme = Theme.of(context).textTheme;
    return SizedBox(
      width: 420,
      child: Card(
        margin: EdgeInsets.zero,
        shape: RoundedRectangleBorder(borderRadius: BorderRadius.circular(8)),
        child: Padding(
          padding: const EdgeInsets.all(12),
          child: Column(
            crossAxisAlignment: CrossAxisAlignment.start,
            children: [
              Row(
                crossAxisAlignment: CrossAxisAlignment.start,
                children: [
                  Expanded(
                    child: Column(
                      crossAxisAlignment: CrossAxisAlignment.start,
                      children: [
                        Text(config.name, style: textTheme.titleSmall),
                        const SizedBox(height: 2),
                        SelectableText(
                          config.id,
                          style: textTheme.bodySmall,
                        ),
                      ],
                    ),
                  ),
                  IconButton(
                    onPressed: onEdit,
                    icon: const Icon(Icons.edit),
                    tooltip: '编辑',
                  ),
                  IconButton(
                    onPressed: onDelete,
                    icon: const Icon(Icons.delete_outline),
                    tooltip: '删除',
                  ),
                ],
              ),
              const SizedBox(height: 8),
              Wrap(
                spacing: 6,
                runSpacing: 6,
                children: [
                  Chip(label: Text(config.providerType)),
                  Chip(label: Text(config.enabled ? '已启用' : '已停用')),
                  if (config.supportsLlm) const Chip(label: Text('聊天')),
                  if (config.supportsCodeModel) const Chip(label: Text('代码生成')),
                  if (config.defaultLlm) const Chip(label: Text('默认聊天')),
                  if (config.defaultCodeModel) const Chip(label: Text('默认代码')),
                  Chip(
                    label: Text(
                      config.apiKeyConfigured
                          ? 'Key ${config.maskedApiKey}'
                          : '未配置 Key',
                    ),
                  ),
                ],
              ),
              const SizedBox(height: 8),
              _ModelField(label: '模型', value: config.modelName),
              _ModelField(label: 'Base URL', value: config.baseUrl),
            ],
          ),
        ),
      ),
    );
  }
}

class _ModelField extends StatelessWidget {
  final String label;
  final String value;

  const _ModelField({required this.label, required this.value});

  @override
  Widget build(BuildContext context) {
    return Padding(
      padding: const EdgeInsets.symmetric(vertical: 2),
      child: Row(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          SizedBox(
            width: 80,
            child: Text(label, style: Theme.of(context).textTheme.bodySmall),
          ),
          Expanded(
            child: SelectableText(
              value,
              style: Theme.of(context).textTheme.bodySmall,
            ),
          ),
        ],
      ),
    );
  }
}

class _ModelProviderDialog extends StatefulWidget {
  final ModelProviderConfigView? config;

  const _ModelProviderDialog({this.config});

  @override
  State<_ModelProviderDialog> createState() => _ModelProviderDialogState();
}

class _ModelProviderDialogState extends State<_ModelProviderDialog> {
  final _formKey = GlobalKey<FormState>();
  late final TextEditingController _nameController;
  late final TextEditingController _baseUrlController;
  late final TextEditingController _modelNameController;
  late final TextEditingController _apiKeyController;
  late String _providerType;
  late bool _enabled;
  late bool _supportsLlm;
  late bool _supportsCodeModel;
  late bool _defaultLlm;
  late bool _defaultCodeModel;

  @override
  void initState() {
    super.initState();
    final config = widget.config;
    _providerType = config?.providerType ?? 'openai';
    _nameController = TextEditingController(text: config?.name ?? '');
    _baseUrlController = TextEditingController(
      text: config?.baseUrl ?? _defaultBaseUrl(_providerType),
    );
    _modelNameController = TextEditingController(text: config?.modelName ?? '');
    _apiKeyController = TextEditingController();
    _enabled = config?.enabled ?? true;
    _supportsLlm = config?.supportsLlm ?? true;
    _supportsCodeModel = config?.supportsCodeModel ?? true;
    _defaultLlm = config?.defaultLlm ?? false;
    _defaultCodeModel = config?.defaultCodeModel ?? false;
  }

  @override
  void dispose() {
    _nameController.dispose();
    _baseUrlController.dispose();
    _modelNameController.dispose();
    _apiKeyController.dispose();
    super.dispose();
  }

  @override
  Widget build(BuildContext context) {
    final editing = widget.config != null;
    return AlertDialog(
      title: Text(editing ? '编辑模型配置' : '新增模型配置'),
      content: SizedBox(
        width: 520,
        child: Form(
          key: _formKey,
          child: SingleChildScrollView(
            child: Column(
              mainAxisSize: MainAxisSize.min,
              children: [
                TextFormField(
                  controller: _nameController,
                  decoration: const InputDecoration(labelText: '名称'),
                  validator: _required('请填写名称'),
                ),
                const SizedBox(height: 8),
                DropdownButtonFormField<String>(
                  initialValue: _providerType,
                  decoration: const InputDecoration(labelText: '供应商类型'),
                  items: const [
                    DropdownMenuItem(value: 'openai', child: Text('OpenAI')),
                    DropdownMenuItem(
                      value: 'anthropic',
                      child: Text('Anthropic / Claude'),
                    ),
                    DropdownMenuItem(
                      value: 'openai-compatible',
                      child: Text('OpenAI 兼容'),
                    ),
                  ],
                  onChanged: (value) {
                    if (value == null) return;
                    final shouldReplaceBaseUrl =
                        _baseUrlController.text.trim().isEmpty ||
                            _baseUrlController.text.trim() ==
                                _defaultBaseUrl(_providerType);
                    setState(() => _providerType = value);
                    if (shouldReplaceBaseUrl) {
                      _baseUrlController.text = _defaultBaseUrl(value);
                    }
                  },
                ),
                const SizedBox(height: 8),
                TextFormField(
                  controller: _modelNameController,
                  decoration: const InputDecoration(
                    labelText: '模型名称',
                    hintText: '例如 gpt-4.1、claude-3-7-sonnet-latest',
                  ),
                  validator: _required('请填写模型名称'),
                ),
                const SizedBox(height: 8),
                TextFormField(
                  controller: _baseUrlController,
                  decoration: const InputDecoration(labelText: 'Base URL'),
                  validator: _required('请填写 Base URL'),
                ),
                const SizedBox(height: 8),
                TextFormField(
                  controller: _apiKeyController,
                  obscureText: true,
                  decoration: InputDecoration(
                    labelText: editing ? '新 API Key' : 'API Key',
                    helperText: editing ? '留空则保留当前 key' : null,
                  ),
                  validator: (value) {
                    if (!editing && (value == null || value.trim().isEmpty)) {
                      return '请填写 API Key';
                    }
                    return null;
                  },
                ),
                const SizedBox(height: 8),
                SwitchListTile(
                  contentPadding: EdgeInsets.zero,
                  value: _enabled,
                  onChanged: (value) => setState(() => _enabled = value),
                  title: const Text('启用'),
                ),
                SwitchListTile(
                  contentPadding: EdgeInsets.zero,
                  value: _supportsLlm,
                  onChanged: (value) => setState(() {
                    _supportsLlm = value;
                    if (!value) _defaultLlm = false;
                  }),
                  title: const Text('用于聊天/路由'),
                ),
                SwitchListTile(
                  contentPadding: EdgeInsets.zero,
                  value: _supportsCodeModel,
                  onChanged: (value) => setState(() {
                    _supportsCodeModel = value;
                    if (!value) _defaultCodeModel = false;
                  }),
                  title: const Text('用于技能代码生成'),
                ),
                SwitchListTile(
                  contentPadding: EdgeInsets.zero,
                  value: _defaultLlm,
                  onChanged: _supportsLlm
                      ? (value) => setState(() => _defaultLlm = value)
                      : null,
                  title: const Text('设为默认聊天模型'),
                ),
                SwitchListTile(
                  contentPadding: EdgeInsets.zero,
                  value: _defaultCodeModel,
                  onChanged: _supportsCodeModel
                      ? (value) => setState(() => _defaultCodeModel = value)
                      : null,
                  title: const Text('设为默认代码模型'),
                ),
              ],
            ),
          ),
        ),
      ),
      actions: [
        TextButton(
          onPressed: () => Navigator.of(context).pop(),
          child: const Text('取消'),
        ),
        FilledButton(
          onPressed: _submit,
          child: const Text('保存'),
        ),
      ],
    );
  }

  FormFieldValidator<String> _required(String message) {
    return (value) => value == null || value.trim().isEmpty ? message : null;
  }

  void _submit() {
    if (!_formKey.currentState!.validate()) return;

    final payload = <String, Object?>{
      'name': _nameController.text.trim(),
      'providerType': _providerType,
      'baseUrl': _baseUrlController.text.trim(),
      'modelName': _modelNameController.text.trim(),
      'enabled': _enabled,
      'supportsLlm': _supportsLlm,
      'supportsCodeModel': _supportsCodeModel,
      'defaultLlm': _defaultLlm,
      'defaultCodeModel': _defaultCodeModel,
    };
    final apiKey = _apiKeyController.text.trim();
    if (apiKey.isNotEmpty) {
      payload['apiKey'] = apiKey;
    }

    Navigator.of(context).pop(payload);
  }

  String _defaultBaseUrl(String providerType) {
    return providerType == 'anthropic'
        ? 'https://api.anthropic.com/v1'
        : 'https://api.openai.com/v1';
  }
}

class _MobileConnectionPlaceholder extends StatelessWidget {
  const _MobileConnectionPlaceholder();

  @override
  Widget build(BuildContext context) {
    return ListTile(
      leading: const Icon(Icons.phone_iphone),
      title: const Text('移动端连接'),
      subtitle: const Text('等待设备配置'),
      shape: RoundedRectangleBorder(
        side: BorderSide(color: Theme.of(context).dividerColor),
        borderRadius: BorderRadius.circular(8),
      ),
    );
  }
}

class _MobileConnectionSection extends StatelessWidget {
  final DeviceStatus status;
  final FrontendRuntimeConfig runtimeConfig;
  final String runtimeConfigError;

  const _MobileConnectionSection({
    required this.status,
    required this.runtimeConfig,
    required this.runtimeConfigError,
  });

  @override
  Widget build(BuildContext context) {
    final configured =
        runtimeConfig.mobileConnection.toDeviceConnection(status);

    return Column(
      crossAxisAlignment: CrossAxisAlignment.start,
      children: [
        Text('移动端连接配置', style: Theme.of(context).textTheme.titleMedium),
        const SizedBox(height: 8),
        if (runtimeConfigError.isNotEmpty)
          Padding(
            padding: const EdgeInsets.only(bottom: 8),
            child: Text(
              '前端配置加载失败: $runtimeConfigError',
              style: TextStyle(color: Theme.of(context).colorScheme.error),
            ),
          ),
        if (runtimeConfig.fallbackUsed && runtimeConfigError.isEmpty)
          Padding(
            padding: const EdgeInsets.only(bottom: 8),
            child: Text(
              '当前使用示例配置，填写 frontend/config/evoforge.local.json 后刷新。',
              style: Theme.of(context).textTheme.bodySmall,
            ),
          ),
        Wrap(
          spacing: 12,
          runSpacing: 12,
          children: [
            _ConnectionConfigTile(
              title: configured.transportLabel,
              icon: configured.transportKind ==
                      DeviceMobileTransportKind.rabbitMqWebStomp
                  ? Icons.hub
                  : Icons.swap_horiz,
              config: configured,
            ),
          ],
        ),
      ],
    );
  }
}

class _ConnectionConfigTile extends StatelessWidget {
  final String title;
  final IconData icon;
  final DeviceMobileConnectionConfig config;

  const _ConnectionConfigTile({
    required this.title,
    required this.icon,
    required this.config,
  });

  @override
  Widget build(BuildContext context) {
    final entries = config.describe().entries.toList();
    return SizedBox(
      width: 520,
      child: DecoratedBox(
        decoration: BoxDecoration(
          border: Border.all(color: Theme.of(context).dividerColor),
          borderRadius: BorderRadius.circular(8),
        ),
        child: Padding(
          padding: const EdgeInsets.all(12),
          child: Column(
            crossAxisAlignment: CrossAxisAlignment.start,
            children: [
              Row(
                children: [
                  Icon(icon),
                  const SizedBox(width: 8),
                  Expanded(
                    child: Text(
                      title,
                      style: Theme.of(context).textTheme.titleSmall,
                    ),
                  ),
                  Chip(
                    avatar: Icon(
                      config.readyForConnection
                          ? Icons.check_circle
                          : Icons.error_outline,
                      size: 16,
                    ),
                    label: Text(config.readyForConnection ? '可连接' : '待补全'),
                  ),
                ],
              ),
              const SizedBox(height: 8),
              ...entries.map(
                (entry) => Padding(
                  padding: const EdgeInsets.symmetric(vertical: 3),
                  child: Row(
                    crossAxisAlignment: CrossAxisAlignment.start,
                    children: [
                      SizedBox(
                        width: 150,
                        child: Text(
                          entry.key,
                          style: Theme.of(context).textTheme.bodySmall,
                        ),
                      ),
                      Expanded(
                        child: SelectableText(
                          entry.value,
                          style: Theme.of(context).textTheme.bodySmall,
                        ),
                      ),
                    ],
                  ),
                ),
              ),
            ],
          ),
        ),
      ),
    );
  }
}

class _SettingTile extends StatelessWidget {
  final String label;
  final String value;

  const _SettingTile({required this.label, required this.value});

  @override
  Widget build(BuildContext context) {
    return SizedBox(
      width: 340,
      child: ListTile(
        title: Text(label),
        subtitle: SelectableText(value),
        shape: RoundedRectangleBorder(
          side: BorderSide(color: Theme.of(context).dividerColor),
          borderRadius: BorderRadius.circular(8),
        ),
      ),
    );
  }
}
