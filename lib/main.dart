import 'package:android_alarm_manager_plus/android_alarm_manager_plus.dart';
import 'package:flutter/material.dart';

import 'pages/home_page.dart';
import 'scheduler/scheduler.dart';

/// 全局导航 key（通知点击后跳转播放页）
final GlobalKey<NavigatorState> navKey = GlobalKey<NavigatorState>();

Future<void> main() async {
  WidgetsFlutterBinding.ensureInitialized();

  // 先启动界面，避免调度初始化失败导致 App 闪退
  runApp(const ScheduledPlayerApp());

  // 精确闹钟初始化：注册回调 + 通知渠道 + 首次调度
  // 初始化失败只影响后台定时触发，不影响 App 正常打开使用
  try {
    await AndroidAlarmManager.initialize();
    // 注入全局导航（通知点击后跳播放页）
    Scheduler.navKey = navKey;
    await Scheduler.init();
    // 处理"由全屏通知启动"：记录待执行任务，交给首页消费。
    // 必须放在 Scheduler.init() 之后：通知插件要先初始化，
    // 否则 getNotificationAppLaunchDetails 可能抛异常并中断首次调度。
    Scheduler.pendingTaskId = await Scheduler.handleLaunchPayload();
  } catch (e) {
    debugPrint('定时调度初始化失败（不影响主界面）: $e');
  }
}

class ScheduledPlayerApp extends StatelessWidget {
  const ScheduledPlayerApp({super.key});

  @override
  Widget build(BuildContext context) {
    return MaterialApp(
      title: '定时播放',
      debugShowCheckedModeBanner: false,
      navigatorKey: navKey,
      theme: ThemeData(
        colorScheme: ColorScheme.fromSeed(seedColor: Colors.indigo),
        useMaterial3: true,
        scaffoldBackgroundColor: const Color(0xFFF7F7FA),
      ),
      home: const HomePage(),
    );
  }
}
