import 'dart:convert';

import 'package:shared_preferences/shared_preferences.dart';

import '../models/task.dart';

/// 任务持久化：shared_preferences 存储 JSON 列表
class TaskStore {
  static const _key = 'play_tasks';
  static const _lastTriggerPrefix = 'last_trigger_';

  /// 读取全部任务
  static Future<List<PlayTask>> load() async {
    final prefs = await SharedPreferences.getInstance();
    final raw = prefs.getString(_key);
    if (raw == null || raw.isEmpty) return [];
    try {
      final list = jsonDecode(raw) as List;
      return list
          .map((e) => PlayTask.fromJson(e as Map<String, dynamic>))
          .toList();
    } catch (_) {
      return [];
    }
  }

  /// 保存全部任务
  static Future<void> save(List<PlayTask> tasks) async {
    final prefs = await SharedPreferences.getInstance();
    await prefs.setString(
        _key, jsonEncode(tasks.map((t) => t.toJson()).toList()));
  }

  /// 记录任务在 [dayKey]（yyyy-MM-dd）已触发过，避免重复触发
  static Future<void> markTriggered(int taskId, String dayKey) async {
    final prefs = await SharedPreferences.getInstance();
    await prefs.setString('$_lastTriggerPrefix$taskId', dayKey);
  }

  /// 查询任务在 [dayKey] 是否已触发过
  static Future<bool> wasTriggered(int taskId, String dayKey) async {
    final prefs = await SharedPreferences.getInstance();
    return prefs.getString('$_lastTriggerPrefix$taskId') == dayKey;
  }
}
