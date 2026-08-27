import 'package:android_alarm_manager_plus/android_alarm_manager_plus.dart';
import 'package:flutter/material.dart';
import 'package:flutter_local_notifications/flutter_local_notifications.dart';
import 'package:url_launcher/url_launcher.dart';

import '../models/task.dart';
import '../storage/task_store.dart';
import '../widgets/player_page.dart';

/// 全屏通知插件单例（后台闹钟 isolate 中也用它发全屏提醒）
final FlutterLocalNotificationsPlugin notifications =
    FlutterLocalNotificationsPlugin();

/// 闹钟回调入口（必须为带 entry-point 注解的顶层函数，后台 isolate 执行）
@pragma('vm:entry-point')
void scheduledPlayerCheck() {
  Scheduler.check();
}

/// 精确闹钟调度器
///
/// 机制（替代原 workmanager 15 分钟轮询，根治"任务时刻被轮询相位跳过"问题）：
/// 1. scheduleNext()：扫描所有启用任务，算出最近一次触发时刻，用 AlarmManager
///    一次性精确闹钟（setExactAndAllowWhileIdle + RTC_WAKEUP）调度到那一秒；
/// 2. 到点回调 check()：找出该时刻应触发的任务并执行，然后立刻调度下一次；
/// 3. 每次执行后重新调度，环环相扣，任务时间即触发时间（秒级精度）。
class Scheduler {
  static const int _alarmId = 10001;

  /// 由全屏通知拉起 App 时，待执行的任务 id（HomePage 启动时消费）
  static int? pendingTaskId;

  /// 全局导航 key（通知点击后跳转播放页用），由 main 注入
  static GlobalKey<NavigatorState>? navKey;

  /// 通知渠道 id
  static const String _channelId = 'alarm_channel';

  /// App 启动时初始化：通知渠道 + Android 13+ 通知权限 + 首次调度
  static Future<void> init() async {
    await _initNotifications();
    await scheduleNext();
  }

  static Future<void> _initNotifications() async {
    await _ensureNotifications();
    // Android 13+ 需要通知权限才能弹全屏提醒（仅主 isolate 请求一次）
    await notifications
        .resolvePlatformSpecificImplementation<
            AndroidFlutterLocalNotificationsPlugin>()
        ?.requestNotificationsPermission();
  }

  /// 初始化通知插件（幂等：主界面与后台 isolate 均可调用）
  static Future<void> _ensureNotifications() async {
    const androidInit = AndroidInitializationSettings('@mipmap/ic_launcher');
    const init = InitializationSettings(android: androidInit);
    try {
      await notifications.initialize(
        init,
        onDidReceiveNotificationResponse: onNotificationTap,
      );
    } catch (_) {
      // 重复初始化失败静默
    }
  }

  /// 计算所有启用任务中最近的下一次触发时刻
  static DateTime? _nextTrigger(DateTime now, List<PlayTask> tasks) {
    DateTime? best;
    for (final t in tasks) {
      if (!t.enabled) continue;
      final at = t.nextRunAt(now);
      if (at == null) continue;
      if (best == null || at.isBefore(best)) best = at;
    }
    return best;
  }

  /// 重新调度最近一个触发点。
  /// 任务新建/编辑/删除/启停后调用（home_page 刷新时自动执行）。
  static Future<void> scheduleNext() async {
    final tasks = await TaskStore.load();
    final next = _nextTrigger(DateTime.now(), tasks);
    if (next == null) return;
    await AndroidAlarmManager.oneShotAt(
      next,
      _alarmId,
      scheduledPlayerCheck,
      exact: true, // setExactAndAllowWhileIdle：准点触发、Doze 省电也唤醒
      wakeup: true, // RTC_WAKEUP：熄屏时唤醒设备
      allowWhileIdle: true,
      rescheduleOnReboot: true, // 重启后自动恢复闹钟
    );
  }

  /// 闹钟到点回调（后台 isolate 执行）
  @pragma('vm:entry-point')
  static Future<void> check() async {
    // 后台 isolate 是独立环境，重新初始化通知插件（幂等）
    await _ensureNotifications();
    final now = DateTime.now();
    final dayKey = _ymd(now);
    final tasks = await TaskStore.load();

    // 找出当前时刻应触发且当日未触发过的任务
    final due = <PlayTask>[];
    for (final t in tasks) {
      if (!t.enabled) continue;
      if (!t.shouldRunAt(now)) continue;
      if (await TaskStore.wasTriggered(t.id, dayKey)) continue;
      due.add(t);
    }

    for (final t in due) {
      await TaskStore.markTriggered(t.id, dayKey);
      await _execute(t);
    }

    // 无论是否触发，都重新调度下一次
    await scheduleNext();
  }

  /// 执行任务（后台 isolate）：
  /// 安卓 10+ 禁止后台启动 Activity/浏览器（尤其 MIUI），
  /// 后台直接 launchUrl 会被系统静默拦截（B站链接打不开的根因）。
  /// 因此一律弹全屏提醒，用户点击后在【前台】执行（executeTaskById）。
  static Future<void> _execute(PlayTask t) async {
    if (t.ct == 'url') {
      await _showAlarm(t, '定时任务「${t.name}」已到点，点击打开网址');
    } else {
      await _showAlarm(t, '定时任务「${t.name}」已到点，点击开始播放');
    }
  }

  /// 前台执行任务（通知点击 / 首页消费统一入口）：
  /// - url：前台打开浏览器（无后台启动限制，100% 成功）
  /// - file：进入播放页
  static Future<void> executeTaskById(int id) async {
    final tasks = await TaskStore.load();
    for (final t in tasks) {
      if (t.id != id) continue;
      if (t.ct == 'url') {
        await _tryOpenUrl(t.url);
      } else {
        final ctx = navKey?.currentContext;
        if (ctx != null) {
          Navigator.push(
              ctx, MaterialPageRoute(builder: (_) => PlayerPage(task: t)));
        }
      }
      return;
    }
  }

  /// 通知点击回调（App 存活/前台时点击通知同样触发，避免任务丢失）
  static void onNotificationTap(NotificationResponse res) {
    final payload = res.payload;
    if (payload == null || !payload.startsWith('play_task_')) return;
    final id = int.tryParse(payload.substring('play_task_'.length));
    if (id != null) executeTaskById(id);
  }

  static Future<bool> _tryOpenUrl(String url) async {
    final uri = Uri.tryParse(url);
    if (uri == null) return false;
    try {
      return await launchUrl(uri, mode: LaunchMode.externalApplication);
    } catch (_) {
      return false;
    }
  }

  /// 全屏通知（闹钟样式）：全屏弹出，点击携带 payload 拉起 App
  static Future<void> _showAlarm(PlayTask t, String body) async {
    const details = AndroidNotificationDetails(
      _channelId,
      '定时任务提醒',
      channelDescription: '定时任务到点提醒',
      importance: Importance.max,
      priority: Priority.high,
      category: AndroidNotificationCategory.alarm,
      fullScreenIntent: true,
      visibility: NotificationVisibility.public,
      playSound: true,
    );
    try {
      await notifications.show(
        t.id, // 通知 id 复用任务 id，同日重复任务自动去重
        '定时播放',
        body,
        const NotificationDetails(android: details),
        payload: 'play_task_${t.id}',
      );
    } catch (_) {
      // 通知发送失败静默（如权限未授予）
    }
  }

  /// 处理全屏通知点击：返回需要立即执行的任务 id（main 里处理跳转）
  /// 返回 null 表示不是由通知启动。
  static Future<int?> handleLaunchPayload() async {
    final details = await notifications.getNotificationAppLaunchDetails();
    if (details == null || !details.didNotificationLaunchApp) return null;
    final payload = details.notificationResponse?.payload;
    if (payload == null || !payload.startsWith('play_task_')) return null;
    return int.tryParse(payload.substring('play_task_'.length));
  }

  static String _ymd(DateTime d) =>
      '${d.year.toString().padLeft(4, '0')}-${d.month.toString().padLeft(2, '0')}-${d.day.toString().padLeft(2, '0')}';
}
