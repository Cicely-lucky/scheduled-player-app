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
    // 关键修复：闹钟回调运行在后台独立 isolate，其 SharedPreferences
    // 内存缓存可能是旧数据（看不到主界面新建的任务）。reload() 强制
    // 从磁盘重读，保证每次都拿到最新任务列表。
    try {
      await prefs.reload();
    } catch (_) {
      // 个别平台不支持 reload，忽略后仍按缓存读取
    }
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
