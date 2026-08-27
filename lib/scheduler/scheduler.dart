import 'package:url_launcher/url_launcher.dart';

import '../models/task.dart';
import '../storage/task_store.dart';

/// 后台定时检查：由 workmanager 周期触发（最小间隔 15 分钟）
class Scheduler {
  static Future<void> runCheck() async {
    final now = DateTime.now();
    final dayKey =
        '${now.year}-${now.month.toString().padLeft(2, '0')}-${now.day.toString().padLeft(2, '0')}';
    final tasks = await TaskStore.load();

    for (final t in tasks) {
      if (!t.enabled) continue;
      if (!t.shouldRunAt(now)) continue;
      if (await TaskStore.wasTriggered(t.id, dayKey)) continue;
      await TaskStore.markTriggered(t.id, dayKey);

      if (t.ct == 'url') {
        // 到点自动打开网址
        await _openUrl(t.url);
      }
      // 音视频文件播放需要前台界面，无法在后台直接拉起；
      // 生产环境建议：前台服务 + 媒体通知（README 中有进阶方案说明）。
      // 骨架中已标记"当日已触发"，用户打开 App 后可在任务列表看到状态。
    }
  }

  static Future<void> _openUrl(String url) async {
    final uri = Uri.tryParse(url);
    if (uri == null) return;
    try {
      final ok = await canLaunchUrl(uri);
      if (ok) {
        // externalApplication：用系统浏览器打开；
        // 注意 Android 10+ 对后台启动 Activity 有限制，建议配合前台服务使用。
        await launchUrl(uri, mode: LaunchMode.externalApplication);
      }
    } catch (_) {
      // 打开失败静默处理（后台环境受限）
    }
  }
}
