import 'dart:convert';

import 'package:flutter/material.dart';

import '../../shared/api/evoforge_api.dart';
import '../../shared/models/skill.dart';
import '../../shared/widgets/page_header.dart';

class SkillsPage extends StatefulWidget {
  final EvoForgeApi api;

  const SkillsPage({super.key, required this.api});

  @override
  State<SkillsPage> createState() => _SkillsPageState();
}

class _SkillsPageState extends State<SkillsPage> {
  List<SkillView> skills = [];
  SkillDetail? selected;
  String output = '';
  bool loading = false;
  bool evaluate = false;

  final TextEditingController nameController = TextEditingController();
  final TextEditingController metadataController = TextEditingController();
  final TextEditingController codeController = TextEditingController();
  final TextEditingController inputController = TextEditingController();
  final TextEditingController promptController = TextEditingController();

  @override
  void initState() {
    super.initState();
    loadSkills();
  }

  @override
  void dispose() {
    nameController.dispose();
    metadataController.dispose();
    codeController.dispose();
    inputController.dispose();
    promptController.dispose();
    super.dispose();
  }

  Future<void> loadSkills() async {
    setState(() => loading = true);
    try {
      final data = await widget.api.loadSkills();
      setState(() => skills = data);
    } catch (e) {
      showMessage('加载技能失败: $e');
    } finally {
      if (mounted) setState(() => loading = false);
    }
  }

  Future<void> loadSkillDetail(String id) async {
    setState(() => loading = true);
    try {
      final detail = await widget.api.loadSkillDetail(id);
      setState(() {
        selected = detail;
        nameController.text = detail.name;
        metadataController.text =
            const JsonEncoder.withIndent('  ').convert(detail.metadata);
        codeController.text = detail.code;
        output = '';
      });
    } catch (e) {
      showMessage('加载详情失败: $e');
    } finally {
      if (mounted) setState(() => loading = false);
    }
  }

  Future<void> createSkill() async {
    if (nameController.text.trim().isEmpty ||
        codeController.text.trim().isEmpty) {
      showMessage('名称和代码不能为空');
      return;
    }
    final metadata = parseMetadata();
    if (metadata == null) return;
    await runAction(
      () => widget.api.createSkill(
        name: nameController.text.trim(),
        code: codeController.text,
        metadata: metadata,
      ),
    );
  }

  Future<void> updateSkill() async {
    final detail = selected;
    if (detail == null) {
      showMessage('请选择技能');
      return;
    }
    final metadata = parseMetadata();
    if (metadata == null) return;
    await runAction(
      () => widget.api.updateSkill(
        detail.id,
        name: nameController.text.trim(),
        code: codeController.text,
        metadata: metadata,
      ),
    );
    await loadSkillDetail(detail.id);
  }

  Future<void> activateSkill() async {
    final detail = selected;
    if (detail == null) {
      showMessage('请选择技能');
      return;
    }
    await runAction(() => widget.api.activateSkill(detail.id));
    await loadSkillDetail(detail.id);
  }

  Future<void> proposeSkill() async {
    final prompt = promptController.text.trim();
    if (prompt.isEmpty) {
      showMessage('请输入生成提示');
      return;
    }
    setState(() => loading = true);
    try {
      final data = await widget.api.proposeSkill(
        name: nameController.text.trim(),
        prompt: prompt,
      );
      setState(() {
        nameController.text = data['name']?.toString() ?? '';
        codeController.text = data['code']?.toString() ?? '';
      });
      showMessage('已加载生成结果');
    } catch (e) {
      showMessage('生成失败: $e');
    } finally {
      if (mounted) setState(() => loading = false);
    }
  }

  Future<void> executeSkill() async {
    final detail = selected;
    if (detail == null) {
      showMessage('请选择技能');
      return;
    }
    setState(() => loading = true);
    try {
      final data = await widget.api.executeSkill(
        detail.id,
        input: inputController.text,
        evaluate: evaluate,
      );
      setState(() => output = const JsonEncoder.withIndent('  ').convert(data));
    } catch (e) {
      showMessage('执行失败: $e');
    } finally {
      if (mounted) setState(() => loading = false);
    }
  }

  Future<void> loadHistory() async {
    final detail = selected;
    if (detail == null) {
      showMessage('请选择技能');
      return;
    }
    await loadJsonOutput(() => widget.api.loadSkillHistory(detail.id));
  }

  Future<void> loadAudit() async {
    final detail = selected;
    if (detail == null) {
      showMessage('请选择技能');
      return;
    }
    await loadJsonOutput(() => widget.api.loadSkillAudit(detail.id));
  }

  Future<void> loadJsonOutput(Future<Object?> Function() action) async {
    setState(() => loading = true);
    try {
      final data = await action();
      setState(() => output = const JsonEncoder.withIndent('  ').convert(data));
    } catch (e) {
      showMessage('加载失败: $e');
    } finally {
      if (mounted) setState(() => loading = false);
    }
  }

  Future<void> runAction(Future<void> Function() action) async {
    setState(() => loading = true);
    try {
      await action();
      await loadSkills();
      showMessage('已保存');
    } catch (e) {
      showMessage('请求失败: $e');
    } finally {
      if (mounted) setState(() => loading = false);
    }
  }

  void clearEditor() {
    setState(() {
      selected = null;
      nameController.clear();
      metadataController.clear();
      codeController.clear();
      inputController.clear();
      promptController.clear();
      output = '';
    });
  }

  Map<String, Object?>? parseMetadata() {
    final text = metadataController.text.trim();
    if (text.isEmpty) {
      return <String, Object?>{};
    }
    try {
      final decoded = jsonDecode(text);
      if (decoded is Map) {
        return Map<String, Object?>.from(decoded);
      }
    } catch (_) {
      showMessage('路由元数据不是有效 JSON');
      return null;
    }
    showMessage('路由元数据必须是 JSON 对象');
    return null;
  }

  void showMessage(String message) {
    if (!mounted) return;
    ScaffoldMessenger.of(
      context,
    ).showSnackBar(SnackBar(content: Text(message)));
  }

  @override
  Widget build(BuildContext context) {
    final isNarrow = MediaQuery.sizeOf(context).width < 900;
    return Column(
      children: [
        PageHeader(
          title: '技能',
          subtitle: '管理 Groovy 技能库',
          actions: [
            IconButton(
              onPressed: loading ? null : loadSkills,
              icon: const Icon(Icons.refresh),
              tooltip: '刷新',
            ),
          ],
        ),
        if (loading) const LinearProgressIndicator(),
        Expanded(
          child: isNarrow
              ? ListView(
                  padding: const EdgeInsets.fromLTRB(16, 0, 16, 24),
                  children: [
                    _SkillList(
                      skills: skills,
                      selectedId: selected?.id,
                      onTap: loadSkillDetail,
                      embedded: true,
                    ),
                    const SizedBox(height: 12),
                    _SkillEditor(
                      selected: selected,
                      nameController: nameController,
                      metadataController: metadataController,
                      promptController: promptController,
                      codeController: codeController,
                      inputController: inputController,
                      output: output,
                      evaluate: evaluate,
                      onEvaluateChanged: (value) =>
                          setState(() => evaluate = value),
                      onNew: clearEditor,
                      onGenerate: proposeSkill,
                      onCreate: createSkill,
                      onUpdate: updateSkill,
                      onActivate: activateSkill,
                      onHistory: loadHistory,
                      onAudit: loadAudit,
                      onExecute: executeSkill,
                    ),
                  ],
                )
              : Row(
                  children: [
                    SizedBox(
                      width: 360,
                      child: Padding(
                        padding: const EdgeInsets.fromLTRB(24, 0, 12, 24),
                        child: _SkillList(
                          skills: skills,
                          selectedId: selected?.id,
                          onTap: loadSkillDetail,
                        ),
                      ),
                    ),
                    const VerticalDivider(width: 1),
                    Expanded(
                      child: Padding(
                        padding: const EdgeInsets.fromLTRB(16, 0, 24, 24),
                        child: SingleChildScrollView(
                          child: _SkillEditor(
                            selected: selected,
                            nameController: nameController,
                            metadataController: metadataController,
                            promptController: promptController,
                            codeController: codeController,
                            inputController: inputController,
                            output: output,
                            evaluate: evaluate,
                            onEvaluateChanged: (value) =>
                                setState(() => evaluate = value),
                            onNew: clearEditor,
                            onGenerate: proposeSkill,
                            onCreate: createSkill,
                            onUpdate: updateSkill,
                            onActivate: activateSkill,
                            onHistory: loadHistory,
                            onAudit: loadAudit,
                            onExecute: executeSkill,
                          ),
                        ),
                      ),
                    ),
                  ],
                ),
        ),
      ],
    );
  }
}

class _SkillList extends StatelessWidget {
  final List<SkillView> skills;
  final String? selectedId;
  final ValueChanged<String> onTap;
  final bool embedded;

  const _SkillList({
    required this.skills,
    required this.selectedId,
    required this.onTap,
    this.embedded = false,
  });

  @override
  Widget build(BuildContext context) {
    if (skills.isEmpty) {
      return const Center(
        child: Padding(padding: EdgeInsets.all(24), child: Text('暂无技能')),
      );
    }
    return ListView.separated(
      shrinkWrap: embedded,
      physics: embedded ? const NeverScrollableScrollPhysics() : null,
      itemCount: skills.length,
      separatorBuilder: (_, __) => const SizedBox(height: 8),
      itemBuilder: (context, index) {
        final skill = skills[index];
        return ListTile(
          title: Text(skill.name, maxLines: 1, overflow: TextOverflow.ellipsis),
          subtitle: Text(
            '${skill.status} · ${skill.version} · ${skill.language}',
          ),
          leading: Icon(
            skill.enabled ? Icons.check_circle : Icons.radio_button_unchecked,
            color: skill.enabled ? Colors.green : Colors.grey,
          ),
          selected: selectedId == skill.id,
          onTap: () => onTap(skill.id),
          shape: RoundedRectangleBorder(
            side: BorderSide(color: Theme.of(context).dividerColor),
            borderRadius: BorderRadius.circular(8),
          ),
        );
      },
    );
  }
}

class _SkillEditor extends StatelessWidget {
  final SkillDetail? selected;
  final TextEditingController nameController;
  final TextEditingController metadataController;
  final TextEditingController promptController;
  final TextEditingController codeController;
  final TextEditingController inputController;
  final String output;
  final bool evaluate;
  final ValueChanged<bool> onEvaluateChanged;
  final VoidCallback onNew;
  final VoidCallback onGenerate;
  final VoidCallback onCreate;
  final VoidCallback onUpdate;
  final VoidCallback onActivate;
  final VoidCallback onHistory;
  final VoidCallback onAudit;
  final VoidCallback onExecute;

  const _SkillEditor({
    required this.selected,
    required this.nameController,
    required this.metadataController,
    required this.promptController,
    required this.codeController,
    required this.inputController,
    required this.output,
    required this.evaluate,
    required this.onEvaluateChanged,
    required this.onNew,
    required this.onGenerate,
    required this.onCreate,
    required this.onUpdate,
    required this.onActivate,
    required this.onHistory,
    required this.onAudit,
    required this.onExecute,
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
                selected == null ? '新建技能' : '编辑技能',
                style: Theme.of(
                  context,
                ).textTheme.titleLarge?.copyWith(fontWeight: FontWeight.w700),
              ),
            ),
            OutlinedButton.icon(
              onPressed: onNew,
              icon: const Icon(Icons.add),
              label: const Text('新建'),
            ),
          ],
        ),
        const SizedBox(height: 12),
        TextField(
          controller: nameController,
          decoration: const InputDecoration(
            labelText: '名称',
            border: OutlineInputBorder(),
          ),
        ),
        const SizedBox(height: 10),
        TextField(
          controller: promptController,
          decoration: const InputDecoration(
            labelText: '生成提示',
            border: OutlineInputBorder(),
          ),
        ),
        const SizedBox(height: 10),
        TextField(
          controller: metadataController,
          minLines: 3,
          maxLines: 5,
          decoration: const InputDecoration(
            labelText: '路由元数据(JSON)',
            alignLabelWithHint: true,
            border: OutlineInputBorder(),
          ),
        ),
        const SizedBox(height: 10),
        Wrap(
          spacing: 8,
          runSpacing: 8,
          children: [
            FilledButton.icon(
              onPressed: onGenerate,
              icon: const Icon(Icons.auto_awesome),
              label: const Text('生成代码'),
            ),
            FilledButton.icon(
              onPressed: onCreate,
              icon: const Icon(Icons.save),
              label: const Text('创建'),
            ),
            FilledButton.icon(
              onPressed: onUpdate,
              icon: const Icon(Icons.sync),
              label: const Text('更新'),
            ),
            OutlinedButton.icon(
              onPressed: onActivate,
              icon: const Icon(Icons.play_arrow),
              label: const Text('激活'),
            ),
            OutlinedButton.icon(
              onPressed: onHistory,
              icon: const Icon(Icons.history),
              label: const Text('历史'),
            ),
            OutlinedButton.icon(
              onPressed: onAudit,
              icon: const Icon(Icons.fact_check),
              label: const Text('审计'),
            ),
          ],
        ),
        const SizedBox(height: 12),
        TextField(
          controller: codeController,
          maxLines: 16,
          decoration: const InputDecoration(
            labelText: 'Groovy 代码',
            alignLabelWithHint: true,
            border: OutlineInputBorder(),
          ),
        ),
        const SizedBox(height: 16),
        Text(
          '运行',
          style: Theme.of(
            context,
          ).textTheme.titleMedium?.copyWith(fontWeight: FontWeight.w700),
        ),
        const SizedBox(height: 8),
        TextField(
          controller: inputController,
          decoration: const InputDecoration(
            labelText: '输入',
            border: OutlineInputBorder(),
          ),
        ),
        const SizedBox(height: 8),
        Row(
          children: [
            Checkbox(
              value: evaluate,
              onChanged: (value) => onEvaluateChanged(value ?? false),
            ),
            const Text('使用 LLM 评估'),
            const Spacer(),
            FilledButton.icon(
              onPressed: onExecute,
              icon: const Icon(Icons.bolt),
              label: const Text('执行'),
            ),
          ],
        ),
        const SizedBox(height: 12),
        Container(
          width: double.infinity,
          constraints: const BoxConstraints(minHeight: 160),
          padding: const EdgeInsets.all(12),
          decoration: BoxDecoration(
            color: Colors.grey.shade50,
            border: Border.all(color: Theme.of(context).dividerColor),
            borderRadius: BorderRadius.circular(8),
          ),
          child: SelectableText(output.isEmpty ? '暂无输出' : output),
        ),
      ],
    );
  }
}
