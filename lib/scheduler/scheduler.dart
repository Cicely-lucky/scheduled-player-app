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
  static const String _muteChannelId = 'alarm_channel_mute';

  /// 调度状态（主 isolate 更新，首页展示）：
  /// 让用户直接看到"闹钟是否注册成功、注册到几点"，
  /// 出错也不再静默，方便定位"定时不生效"问题。
  static String status = '未调度';

  /// 通知链路自检结果（App 启动时在主 isolate 里实测一次发通知，
  /// 结果显示在首页）：闹钟触发后最终靠通知提醒用户，这条链路
  /// 是否正常直接决定"到点有没有反应"。
  static String notifSelfTest = '';

  /// 自检通知的 id（与任务 id 空间区分开）
  static const int _selfTestId = 999999;

  /// App 启动时初始化：通知渠道 + Android 13+ 通知权限 + 通知自检 + 首次调度
  static Future<void> init() async {
    await _initNotifications();
    await _selfTestNotifications();
    await scheduleNext();
  }

  /// 前台通知自检：启动时用与闹钟提醒相同的渠道发一条测试通知，
  /// 3 秒后自动撤掉。成功与否写入 [notifSelfTest] 并打日志。
  /// 之前"闹钟响了但毫无反应"的根因是通知在原生层静默失败
  /// （错误被 Dart 端 catch 吞掉），自检让这类问题当场暴露。
  static Future<void> _selfTestNotifications() async {
    try {
      const details = AndroidNotificationDetails(
        _channelId,
        '定时任务提醒',
        channelDescription: '定时任务到点提醒',
        importance: Importance.max,
        priority: Priority.high,
        category: AndroidNotificationCategory.alarm,
        visibility: NotificationVisibility.public,
      );
      await notifications.show(
        _selfTestId,
        '通知自检',
        '通知通道正常，3 秒后自动消失',
        const NotificationDetails(android: details),
      );
      notifSelfTest = '通知自检通过';
      debugPrint('SP-Alarm self-test passed');
      // 3 秒后自动撤掉自检通知（避免停留在状态栏）
      Future.delayed(const Duration(seconds: 3), () async {
        try {
          await notifications.cancel(_selfTestId);
        } catch (_) {}
      });
    } catch (e) {
      notifSelfTest = '通知自检失败：$e';
      debugPrint('SP-Alarm self-test error: $e');
    }
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
    } catch (e) {
      // 重复初始化可忽略，但其他错误必须打日志（后台 isolate 是否能初始化
      // 通知插件直接决定"到点有没有提醒"）
      debugPrint('SP-Alarm notif init error: $e');
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
    try {
      final tasks = await TaskStore.load();
      final next = _nextTrigger(DateTime.now(), tasks);
      if (next == null) {
        status = '没有待触发的任务';
        return;
      }
      await AndroidAlarmManager.oneShotAt(
        next,
        _alarmId,
        scheduledPlayerCheck,
        // setAlarmClock：与系统时钟同级的"用户闹钟"，MIUI GreezeManager
        // 不敢扣（setExact 会被 cached alarm! 挂起，扣到进程解冻才补投）。
        // 代价是状态栏会显示闹钟图标——正好提醒用户"定时已生效"。
        alarmClock: true,
        exact: true, // 准点触发、Doze 省电也唤醒
        wakeup: true, // RTC_WAKEUP：熄屏时唤醒设备
        allowWhileIdle: true,
        rescheduleOnReboot: true, // 重启后自动恢复闹钟
      );
      status = '闹钟已注册：${_fmt(next)} 准点触发';
      debugPrint('SP-Alarm scheduleNext -> $_fmt(next)');
    } catch (e) {
      // 调度失败不再静默：记录到状态并打日志（logcat 过滤 SP-Alarm）
      status = '调度失败：$e';
      debugPrint('SP-Alarm scheduleNext error: $e');
    }
  }

  static String _fmt(DateTime d) =>
      '${d.month}/${d.day} ${d.hour.toString().padLeft(2, '0')}:${d.minute.toString().padLeft(2, '0')}';

  /// 闹钟到点回调（后台 isolate 执行）
  @pragma('vm:entry-point')
  static Future<void> check() async {
    // 后台 isolate 中任何异常都会让回调静默死亡（表现为"到点无反应"），
    // 整体 try/catch 并打日志，logcat 过滤 SP-Alarm 即可看到全过程。
    try {
      debugPrint('SP-Alarm check fired');
      // 后台 isolate 是独立环境，重新初始化通知插件（幂等）
      await _ensureNotifications();
      final now = DateTime.now();
      final dayKey = _ymd(now);
      final tasks = await TaskStore.load();
      debugPrint('SP-Alarm tasks loaded: ${tasks.length}');

      // 容错窗口：闹钟实际触发时刻可能有几秒到几分钟的系统偏差
      // （提前/延后触发、系统合并唤醒、进程冷启动耗时），回溯最近
      // 5 分钟内的每个分钟时刻逐一匹配，避免"闹钟 16:46:59 触发但
      // 任务设的是 16:47"这类跨分钟偏差导致任务被永久跳过。
      // wasTriggered 按天去重，不会重复触发。
      final due = <PlayTask>[];
      for (final t in tasks) {
        if (!t.enabled) continue;
        bool hit = false;
        for (var back = 0; back <= 5 && !hit; back++) {
          final m = now.subtract(Duration(minutes: back));
          if (t.shouldRunAt(m)) {
            hit = true;
            break;
          }
        }
        if (!hit) continue;
        if (await TaskStore.wasTriggered(t.id, dayKey)) continue;
        due.add(t);
      }
      debugPrint('SP-Alarm due tasks: ${due.length}');

      for (final t in due) {
        await TaskStore.markTriggered(t.id, dayKey);
        await _execute(t);
      }

      // 无论是否触发，都重新调度下一次
      await scheduleNext();
    } catch (e) {
      debugPrint('SP-Alarm check error: $e');
    }
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

  /// 通知 id 必须是 32 位 int（Android 系统限制），而任务 id 是毫秒
  /// 时间戳（64 位）。哈希折叠到 31 位正整数；同一任务哈希稳定，
  /// 重复发通知时仍会覆盖旧通知。
  static int _notifId(int taskId) => taskId.hashCode & 0x7FFFFFFF;

  /// 全屏通知（闹钟样式）：全屏弹出，点击携带 payload 拉起 App。
  /// [mute] 为 true 时走静音渠道：只亮屏弹提醒，不响铃不震动。
  static Future<void> _showAlarm(PlayTask t, String body) async {
    final mute = t.mute;
    // 渠道声音属性在创建后不可变，响铃/静音拆两个渠道按需切换
    final details = AndroidNotificationDetails(
      mute ? _muteChannelId : _channelId,
      mute ? '定时任务提醒（静音）' : '定时任务提醒',
      channelDescription: mute ? '到点只亮屏提醒，不响铃不震动' : '定时任务到点提醒',
      importance: Importance.max,
      priority: Priority.high,
      category: AndroidNotificationCategory.alarm,
      fullScreenIntent: true,
      visibility: NotificationVisibility.public,
      playSound: !mute,
      enableVibration: !mute,
    );
    try {
      debugPrint('SP-Alarm showing notification (task ${t.id}, mute=$mute)');
      await notifications.show(
        _notifId(t.id), // 通知 id：任务 id 哈希成 32 位（Android 限制），
        // 此前直接用 64 位毫秒时间戳导致 Invalid argument 异常，通知从未发出
        '定时播放',
        body,
        NotificationDetails(android: details),
        payload: 'play_task_${t.id}',
      );
      debugPrint('SP-Alarm notification shown (task ${t.id})');
    } catch (e) {
      // 通知发送失败绝不再静默：这里是"闹钟响了但毫无反应"的
      // 断点所在（如 invalid_icon / 通知权限被拒等原生错误）
      debugPrint('SP-Alarm show error: $e');
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
