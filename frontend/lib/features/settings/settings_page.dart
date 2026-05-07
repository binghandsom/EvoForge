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
  String runtimeConfigError = '';
  bool loading = false;
  bool configLoading = false;
  bool modelConfigLoading = false;

  @override
  void initState() {
    super.initState();
    loadAll();
  }

  Future<void> loadAll() async {
    await Future.wait([loadStatus(), loadRuntimeConfig(), loadModelConfigs()]);
  }

  Future<void> loadStatus() async {
    setState(() => loading = true);
    try {
      final data = await widget.api.loadDeviceStatus();
      if (mounted) setState(() => status = data);
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
        if (loading || configLoading || modelConfigLoading)
          const LinearProgressIndicator(),
        Expanded(
          child: ListView(
            children: [
              Padding(
                padding: const EdgeInsets.fromLTRB(24, 4, 24, 12),
                child: _SettingsBody(
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
  final FrontendRuntimeConfig runtimeConfig;
  final String runtimeConfigError;

  const _SettingsBody({
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
        const _SettingTile(label: 'API Base URL', value: apiBaseUrl),
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
