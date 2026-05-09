import 'dart:async';

import 'package:flutter/material.dart';

import '../features/command_center/command_center_page.dart';
import '../features/devices/devices_page.dart';
import '../features/settings/settings_page.dart';
import '../features/self_learning/self_learning_page.dart';
import '../features/skills/skills_page.dart';
import '../features/tasks/tasks_page.dart';
import '../shared/api/evoforge_api.dart';
import '../shared/config/frontend_runtime_config.dart';
import '../shared/messaging/device_mobile_controller.dart';
import '../shared/messaging/device_mobile_controller_factory.dart';
import '../shared/messaging/evoforge_message_bus_client.dart';

class EvoForgeApp extends StatelessWidget {
  const EvoForgeApp({super.key});

  @override
  Widget build(BuildContext context) {
    return MaterialApp(
      title: 'EvoForge Console',
      debugShowCheckedModeBanner: false,
      theme: ThemeData(
        colorScheme: ColorScheme.fromSeed(seedColor: const Color(0xFF3F51B5)),
        useMaterial3: true,
        visualDensity: VisualDensity.compact,
      ),
      home: const ConsoleShell(),
    );
  }
}

class ConsoleShell extends StatefulWidget {
  const ConsoleShell({super.key});

  @override
  State<ConsoleShell> createState() => _ConsoleShellState();
}

class _ConsoleShellState extends State<ConsoleShell> {
  EvoForgeApi? api;
  DeviceMobileController? busController;
  FrontendRuntimeConfig? runtimeConfig;
  String startupError = '';
  bool startupLoading = true;
  int selectedIndex = 0;

  List<_Destination> destinations = const [];

  @override
  void initState() {
    super.initState();
    configureMessageBus();
  }

  @override
  void dispose() {
    final currentApi = api;
    if (currentApi != null) {
      unawaited(currentApi.close());
    }
    unawaited(busController?.stop());
    super.dispose();
  }

  Future<void> configureMessageBus() async {
    if (mounted) {
      setState(() {
        startupLoading = true;
        startupError = '';
      });
    }
    EvoForgeApi? nextApi;
    DeviceMobileController? nextController;
    try {
      final runtime = await FrontendRuntimeConfig.load();
      final connection = runtime.mobileConnection.toDirectDeviceConnection();
      if (!connection.readyForConnection) {
        final oldApi = api;
        final oldController = busController;
        if (!mounted) return;
        setState(() {
          runtimeConfig = runtime;
          startupLoading = false;
          api = null;
          busController = null;
          destinations = const [];
        });
        unawaited(oldController?.stop());
        unawaited(oldApi?.close());
        return;
      }

      nextController = DeviceMobileControllerFactory().create(connection);
      await nextController.start();
      nextApi = EvoForgeApi(
        messageBus: EvoForgeMessageBusClient(
          transport: nextController.transport,
          commandFactory: nextController.session.commandFactory,
        ),
      );
      if (!mounted) {
        await nextController.stop();
        await nextApi.close();
        return;
      }

      final oldApi = api;
      final oldController = busController;
      setState(() {
        runtimeConfig = runtime;
        api = nextApi;
        busController = nextController;
        startupLoading = false;
        destinations = buildDestinations(nextApi!);
        if (selectedIndex >= destinations.length) selectedIndex = 0;
      });
      unawaited(oldController?.stop());
      unawaited(oldApi?.close());
    } catch (error) {
      await nextController?.stop();
      await nextApi?.close();
      final oldApi = api;
      final oldController = busController;
      if (!mounted) return;
      setState(() {
        startupError = error.toString();
        startupLoading = false;
        api = null;
        busController = null;
        destinations = const [];
      });
      unawaited(oldController?.stop());
      unawaited(oldApi?.close());
    }
  }

  List<_Destination> buildDestinations(EvoForgeApi api) {
    return [
      _Destination(
        label: '指挥台',
        icon: Icons.terminal,
        page: CommandCenterPage(api: api),
      ),
      _Destination(
        label: '设备',
        icon: Icons.devices,
        page: DevicesPage(api: api),
      ),
      _Destination(
        label: '任务',
        icon: Icons.timeline,
        page: TasksPage(api: api),
      ),
      _Destination(
        label: '技能',
        icon: Icons.auto_fix_high,
        page: SkillsPage(api: api),
      ),
      _Destination(
        label: '自学习',
        icon: Icons.psychology,
        page: SelfLearningPage(api: api),
      ),
      _Destination(
        label: '设置',
        icon: Icons.settings,
        page: SettingsPage(api: api),
      ),
    ];
  }

  @override
  Widget build(BuildContext context) {
    final activeApi = api;
    if (startupLoading || activeApi == null) {
      return _MessageBusStartupPage(
        loading: startupLoading,
        runtimeConfig: runtimeConfig,
        error: startupError,
        onRetry: configureMessageBus,
      );
    }

    final isNarrow = MediaQuery.sizeOf(context).width < 760;

    return Scaffold(
      appBar: isNarrow
          ? AppBar(
              title: Text(destinations[selectedIndex].label),
              centerTitle: false,
            )
          : null,
      bottomNavigationBar: isNarrow
          ? NavigationBar(
              selectedIndex: selectedIndex,
              onDestinationSelected: (value) =>
                  setState(() => selectedIndex = value),
              destinations: destinations
                  .map(
                    (item) => NavigationDestination(
                      icon: Icon(item.icon),
                      label: item.label,
                    ),
                  )
                  .toList(),
            )
          : null,
      body: Row(
        children: [
          if (!isNarrow)
            NavigationRail(
              selectedIndex: selectedIndex,
              onDestinationSelected: (value) =>
                  setState(() => selectedIndex = value),
              labelType: NavigationRailLabelType.all,
              leading: const Padding(
                padding: EdgeInsets.symmetric(vertical: 16),
                child: Icon(Icons.hub),
              ),
              destinations: destinations
                  .map(
                    (item) => NavigationRailDestination(
                      icon: Icon(item.icon),
                      label: Text(item.label),
                    ),
                  )
                  .toList(),
            ),
          if (!isNarrow) const VerticalDivider(width: 1),
          Expanded(
            child: IndexedStack(
              index: selectedIndex,
              children: destinations.map((item) => item.page).toList(),
            ),
          ),
        ],
      ),
    );
  }
}

class _Destination {
  final String label;
  final IconData icon;
  final Widget page;

  _Destination({required this.label, required this.icon, required this.page});
}

class _MessageBusStartupPage extends StatelessWidget {
  final bool loading;
  final FrontendRuntimeConfig? runtimeConfig;
  final String error;
  final VoidCallback onRetry;

  const _MessageBusStartupPage({
    required this.loading,
    required this.runtimeConfig,
    required this.error,
    required this.onRetry,
  });

  @override
  Widget build(BuildContext context) {
    final theme = Theme.of(context);
    final config = runtimeConfig;
    final connection = config?.mobileConnection.toDirectDeviceConnection();
    final source = config == null
        ? frontendConfigAsset
        : config.fallbackUsed
            ? '${config.sourceAsset} (example fallback)'
            : config.sourceAsset;
    final ready = connection?.readyForConnection == true;
    final title = loading
        ? '正在连接消息总线'
        : error.isNotEmpty
            ? '消息总线初始化失败'
            : '需要配置消息总线';
    final body = loading
        ? '正在读取 $frontendConfigAsset，并准备 RabbitMQ Web STOMP / JSON relay 连接。'
        : error.isNotEmpty
            ? error
            : '前端现在默认只通过消息队列请求数据和发送任务。请在 frontend/config/evoforge.local.json 配置可用的 mobileConnection。';

    return Scaffold(
      appBar: AppBar(title: const Text('EvoForge Console')),
      body: Center(
        child: ConstrainedBox(
          constraints: const BoxConstraints(maxWidth: 680),
          child: Padding(
            padding: const EdgeInsets.all(24),
            child: Card(
              shape: RoundedRectangleBorder(
                borderRadius: BorderRadius.circular(8),
              ),
              child: Padding(
                padding: const EdgeInsets.all(20),
                child: Column(
                  mainAxisSize: MainAxisSize.min,
                  crossAxisAlignment: CrossAxisAlignment.start,
                  children: [
                    Row(
                      children: [
                        Icon(
                          loading
                              ? Icons.sync
                              : ready
                                  ? Icons.hub
                                  : Icons.info_outline,
                          color: theme.colorScheme.primary,
                        ),
                        const SizedBox(width: 10),
                        Expanded(
                          child: Text(
                            title,
                            style: theme.textTheme.titleLarge,
                          ),
                        ),
                      ],
                    ),
                    const SizedBox(height: 12),
                    Text(body),
                    const SizedBox(height: 16),
                    Wrap(
                      spacing: 8,
                      runSpacing: 8,
                      children: [
                        Chip(label: Text('配置: $source')),
                        if (connection != null)
                          Chip(label: Text('通道: ${connection.transportLabel}')),
                        if (connection != null)
                          Chip(label: Text('Ready: ${ready ? 'yes' : 'no'}')),
                      ],
                    ),
                    if (!loading) ...[
                      const SizedBox(height: 16),
                      Align(
                        alignment: Alignment.centerRight,
                        child: FilledButton.icon(
                          onPressed: onRetry,
                          icon: const Icon(Icons.refresh),
                          label: const Text('重新加载配置'),
                        ),
                      ),
                    ],
                  ],
                ),
              ),
            ),
          ),
        ),
      ),
    );
  }
}
