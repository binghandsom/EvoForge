import 'dart:async';

import 'package:flutter/material.dart';

import '../features/command_center/command_center_page.dart';
import '../features/devices/devices_page.dart';
import '../features/settings/settings_page.dart';
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
  EvoForgeApi api = EvoForgeApi();
  DeviceMobileController? busController;
  int selectedIndex = 0;

  late List<_Destination> destinations = buildDestinations();

  @override
  void initState() {
    super.initState();
    configureMessageBus();
  }

  @override
  void dispose() {
    unawaited(api.close());
    unawaited(busController?.stop());
    super.dispose();
  }

  Future<void> configureMessageBus() async {
    final runtime = await FrontendRuntimeConfig.load();
    final connection = runtime.mobileConnection.toDirectDeviceConnection();
    if (!connection.readyForConnection) {
      return;
    }
    final controller = DeviceMobileControllerFactory().create(connection);
    final busApi = EvoForgeApi(
      messageBus: EvoForgeMessageBusClient(
        transport: controller.transport,
        commandFactory: controller.session.commandFactory,
      ),
    );
    if (!mounted) {
      await busApi.close();
      return;
    }
    setState(() {
      api = busApi;
      busController = controller;
      destinations = buildDestinations();
    });
  }

  List<_Destination> buildDestinations() {
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
        label: '设置',
        icon: Icons.settings,
        page: SettingsPage(api: api),
      ),
    ];
  }

  @override
  Widget build(BuildContext context) {
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
