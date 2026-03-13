import 'dart:convert';

import 'package:flutter/material.dart';
import 'package:http/http.dart' as http;

const apiBaseUrl = String.fromEnvironment('API_BASE_URL', defaultValue: 'http://localhost:8080');

void main() {
  runApp(const EvoForgeApp());
}

class EvoForgeApp extends StatelessWidget {
  const EvoForgeApp({super.key});

  @override
  Widget build(BuildContext context) {
    return MaterialApp(
      title: 'EvoForge Web Console',
      theme: ThemeData(
        colorScheme: ColorScheme.fromSeed(seedColor: Colors.indigo),
        useMaterial3: true,
      ),
      home: const SkillDashboard(),
    );
  }
}

class SkillDashboard extends StatefulWidget {
  const SkillDashboard({super.key});

  @override
  State<SkillDashboard> createState() => _SkillDashboardState();
}

class _SkillDashboardState extends State<SkillDashboard> {
  List<SkillView> skills = [];
  SkillDetail? selected;
  String output = '';
  bool loading = false;
  bool evaluate = false;

  final TextEditingController nameController = TextEditingController();
  final TextEditingController codeController = TextEditingController();
  final TextEditingController inputController = TextEditingController();
  final TextEditingController promptController = TextEditingController();

  @override
  void initState() {
    super.initState();
    loadSkills();
  }

  Future<void> loadSkills() async {
    setState(() => loading = true);
    try {
      final response = await http.get(Uri.parse('$apiBaseUrl/api/skills'));
      final data = jsonDecode(response.body) as List<dynamic>;
      setState(() {
        skills = data.map((e) => SkillView.fromJson(e as Map<String, dynamic>)).toList();
      });
    } catch (e) {
      showMessage('Failed to load skills: $e');
    } finally {
      setState(() => loading = false);
    }
  }

  Future<void> loadSkillDetail(String id) async {
    setState(() => loading = true);
    try {
      final response = await http.get(Uri.parse('$apiBaseUrl/api/skills/$id'));
      final data = jsonDecode(response.body) as Map<String, dynamic>;
      final detail = SkillDetail.fromJson(data);
      setState(() {
        selected = detail;
        nameController.text = detail.name;
        codeController.text = detail.code;
        output = '';
      });
    } catch (e) {
      showMessage('Failed to load skill: $e');
    } finally {
      setState(() => loading = false);
    }
  }

  Future<void> createSkill() async {
    if (nameController.text.trim().isEmpty || codeController.text.trim().isEmpty) {
      showMessage('Name and code are required');
      return;
    }
    final payload = {
      'name': nameController.text.trim(),
      'code': codeController.text,
      'enabled': false,
    };
    await sendAndReload('POST', '/api/skills', payload);
  }

  Future<void> updateSkill() async {
    if (selected == null) {
      showMessage('Select a skill to update');
      return;
    }
    final payload = {
      'name': nameController.text.trim(),
      'code': codeController.text,
    };
    await sendAndReload('PUT', '/api/skills/${selected!.id}', payload);
    await loadSkillDetail(selected!.id);
  }

  Future<void> activateSkill() async {
    if (selected == null) {
      showMessage('Select a skill to activate');
      return;
    }
    await sendAndReload('POST', '/api/skills/${selected!.id}/activate', {});
    await loadSkillDetail(selected!.id);
  }

  Future<void> loadHistory() async {
    if (selected == null) {
      showMessage('Select a skill to view history');
      return;
    }
    try {
      final response = await http.get(Uri.parse('$apiBaseUrl/api/skills/${selected!.id}/history'));
      final data = jsonDecode(response.body);
      setState(() {
        output = jsonEncode(data, indent: 2);
      });
    } catch (e) {
      showMessage('Failed to load history: $e');
    }
  }

  Future<void> loadAudit() async {
    if (selected == null) {
      showMessage('Select a skill to view audit log');
      return;
    }
    try {
      final response = await http.get(Uri.parse('$apiBaseUrl/api/audit/${selected!.id}'));
      final data = jsonDecode(response.body);
      setState(() {
        output = jsonEncode(data, indent: 2);
      });
    } catch (e) {
      showMessage('Failed to load audit log: $e');
    }
  }

  Future<void> executeSkill() async {
    if (selected == null) {
      showMessage('Select a skill to execute');
      return;
    }
    final payload = {
      'input': inputController.text,
      'evaluate': evaluate,
    };
    try {
      final response = await http.post(
        Uri.parse('$apiBaseUrl/api/skills/${selected!.id}/execute'),
        headers: {'Content-Type': 'application/json'},
        body: jsonEncode(payload),
      );
      final data = jsonDecode(response.body) as Map<String, dynamic>;
      setState(() {
        output = jsonEncode(data, indent: 2);
      });
    } catch (e) {
      showMessage('Execution failed: $e');
    }
  }

  Future<void> proposeSkill() async {
    final prompt = promptController.text.trim();
    if (prompt.isEmpty) {
      showMessage('Enter a prompt first');
      return;
    }
    try {
      final response = await http.post(
        Uri.parse('$apiBaseUrl/api/skills/propose'),
        headers: {'Content-Type': 'application/json'},
        body: jsonEncode({'name': nameController.text.trim(), 'prompt': prompt}),
      );
      final data = jsonDecode(response.body) as Map<String, dynamic>;
      setState(() {
        nameController.text = (data['name'] ?? '').toString();
        codeController.text = (data['code'] ?? '').toString();
      });
      showMessage('Proposal loaded into editor');
    } catch (e) {
      showMessage('Proposal failed: $e');
    }
  }

  Future<void> sendAndReload(String method, String path, Map<String, Object?> payload) async {
    try {
      final uri = Uri.parse('$apiBaseUrl$path');
      http.Response response;
      if (method == 'POST') {
        response = await http.post(uri, headers: {'Content-Type': 'application/json'}, body: jsonEncode(payload));
      } else {
        response = await http.put(uri, headers: {'Content-Type': 'application/json'}, body: jsonEncode(payload));
      }
      if (response.statusCode >= 400) {
        showMessage('Request failed: ${response.statusCode}');
        return;
      }
      await loadSkills();
      showMessage('Saved');
    } catch (e) {
      showMessage('Request failed: $e');
    }
  }

  void showMessage(String message) {
    if (!mounted) return;
    ScaffoldMessenger.of(context).showSnackBar(
      SnackBar(content: Text(message)),
    );
  }

  void clearEditor() {
    setState(() {
      selected = null;
      nameController.clear();
      codeController.clear();
      inputController.clear();
      output = '';
    });
  }

  Color statusColor(String status) {
    switch (status.toLowerCase()) {
      case 'active':
        return Colors.green;
      case 'review':
        return Colors.orange;
      case 'deprecated':
        return Colors.red;
      default:
        return Colors.blueGrey;
    }
  }

  @override
  Widget build(BuildContext context) {
    return Scaffold(
      appBar: AppBar(
        title: const Text('EvoForge Web Console'),
        actions: [
          TextButton(
            onPressed: loadSkills,
            child: const Text('Refresh', style: TextStyle(color: Colors.white)),
          ),
        ],
      ),
      body: Row(
        children: [
          Expanded(
            flex: 1,
            child: Container(
              color: Colors.grey.shade100,
              child: Column(
                children: [
                  const Padding(
                    padding: EdgeInsets.all(12),
                    child: Text('Skills', style: TextStyle(fontSize: 18, fontWeight: FontWeight.bold)),
                  ),
                  if (loading) const LinearProgressIndicator(),
                  Expanded(
                    child: ListView.builder(
                      itemCount: skills.length,
                      itemBuilder: (context, index) {
                        final skill = skills[index];
                        return ListTile(
                          title: Text(skill.name),
                          subtitle: Text('${skill.status} • ${skill.id}'),
                          trailing: Icon(skill.enabled ? Icons.check_circle : Icons.remove_circle,
                              color: skill.enabled ? Colors.green : Colors.grey),
                          selected: selected?.id == skill.id,
                          onTap: () => loadSkillDetail(skill.id),
                        );
                      },
                    ),
                  ),
                ],
              ),
            ),
          ),
          Expanded(
            flex: 2,
            child: Padding(
              padding: const EdgeInsets.all(16),
              child: SingleChildScrollView(
                child: Column(
                  crossAxisAlignment: CrossAxisAlignment.start,
                  children: [
                    Row(
                      children: [
                        Text(selected == null ? 'Create Skill' : 'Edit Skill',
                            style: const TextStyle(fontSize: 18, fontWeight: FontWeight.bold)),
                        const SizedBox(width: 12),
                        TextButton(onPressed: clearEditor, child: const Text('New')),
                        if (selected != null)
                          Container(
                            margin: const EdgeInsets.only(left: 12),
                            padding: const EdgeInsets.symmetric(horizontal: 10, vertical: 4),
                            decoration: BoxDecoration(
                              color: statusColor(selected!.status),
                              borderRadius: BorderRadius.circular(12),
                            ),
                            child: Text(selected!.status, style: const TextStyle(color: Colors.white)),
                          ),
                      ],
                    ),
                    const SizedBox(height: 8),
                    TextField(
                      controller: nameController,
                      decoration: const InputDecoration(labelText: 'Skill Name'),
                    ),
                    const SizedBox(height: 8),
                    TextField(
                      controller: promptController,
                      decoration: const InputDecoration(labelText: 'Prompt for Code Model'),
                    ),
                    const SizedBox(height: 8),
                    Wrap(
                      spacing: 8,
                      runSpacing: 8,
                      children: [
                        ElevatedButton(onPressed: proposeSkill, child: const Text('Generate Code')),
                        ElevatedButton(onPressed: createSkill, child: const Text('Create')),
                        ElevatedButton(onPressed: updateSkill, child: const Text('Update')),
                        ElevatedButton(onPressed: activateSkill, child: const Text('Activate')),
                        OutlinedButton(onPressed: loadHistory, child: const Text('History')),
                        OutlinedButton(onPressed: loadAudit, child: const Text('Audit')),
                      ],
                    ),
                    const SizedBox(height: 12),
                    TextField(
                      controller: codeController,
                      maxLines: 16,
                      decoration: const InputDecoration(
                        labelText: 'Groovy Code',
                        alignLabelWithHint: true,
                        border: OutlineInputBorder(),
                      ),
                    ),
                    const SizedBox(height: 16),
                    const Text('Run Skill', style: TextStyle(fontSize: 16, fontWeight: FontWeight.bold)),
                    const SizedBox(height: 8),
                    TextField(
                      controller: inputController,
                      decoration: const InputDecoration(labelText: 'Input'),
                    ),
                    const SizedBox(height: 8),
                    Row(
                      children: [
                        Checkbox(
                          value: evaluate,
                          onChanged: (value) => setState(() => evaluate = value ?? false),
                        ),
                        const Text('Evaluate with LLM'),
                      ],
                    ),
                    const SizedBox(height: 8),
                    ElevatedButton(onPressed: executeSkill, child: const Text('Execute')),
                    const SizedBox(height: 12),
                    const Text('Output', style: TextStyle(fontWeight: FontWeight.bold)),
                    const SizedBox(height: 4),
                    Container(
                      width: double.infinity,
                      padding: const EdgeInsets.all(12),
                      decoration: BoxDecoration(
                        border: Border.all(color: Colors.grey.shade300),
                        borderRadius: BorderRadius.circular(6),
                      ),
                      child: Text(output.isEmpty ? 'No output yet.' : output),
                    ),
                  ],
                ),
              ),
            ),
          ),
        ],
      ),
    );
  }
}

class SkillView {
  final String id;
  final String name;
  final bool enabled;
  final String status;

  SkillView({required this.id, required this.name, required this.enabled, required this.status});

  factory SkillView.fromJson(Map<String, dynamic> json) {
    return SkillView(
      id: json['id']?.toString() ?? '',
      name: json['name']?.toString() ?? '',
      enabled: json['enabled'] == true,
      status: json['status']?.toString() ?? 'DRAFT',
    );
  }
}

class SkillDetail {
  final String id;
  final String name;
  final String code;
  final String status;

  SkillDetail({required this.id, required this.name, required this.code, required this.status});

  factory SkillDetail.fromJson(Map<String, dynamic> json) {
    return SkillDetail(
      id: json['id']?.toString() ?? '',
      name: json['name']?.toString() ?? '',
      code: json['code']?.toString() ?? '',
      status: json['status']?.toString() ?? 'DRAFT',
    );
  }
}
