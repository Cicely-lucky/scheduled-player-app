import 'dart:async' show unawaited;

import 'package:android_alarm_manager_plus/android_alarm_manager_plus.dart';
import 'package:flutter/material.dart';

import 'pages/home_page.dart';
import 'scheduler/scheduler.dart';

/// 全局导航 key（通知点击后跳转播放页）
final GlobalKey<NavigatorState> navKey = GlobalKey<NavigatorState>();

Future<void> main() async {
  WidgetsFlutterBinding.ensureInitialized();

  // runApp 之前完成两件轻量初始化（各几十毫秒，不阻塞首帧）：
  // 1. 闹钟插件注册回调；
  // 2. 通知插件初始化并取出"点击通知冷启动"的待执行任务。
  //    必须在 runApp 前：首页 initState 里就会消费 pendingTaskId，
  //    若赋值晚于首页构建，点通知冷启动的任务会被静默丢弃。
  // 完整初始化（权限/自检/首次调度）放在 runApp 之后后台执行，
  // 即使失败也不影响 App 正常打开使用。
  try {
    await AndroidAlarmManager.initialize();
    Scheduler.navKey = navKey;
    Scheduler.pendingTaskId = await Scheduler.prepareLaunch();
  } catch (e) {
    debugPrint('定时调度初始化失败（不影响主界面）: $e');
  }

  runApp(const ScheduledPlayerApp());

  // 通知渠道 + 权限 + 自检 + 首次调度（幂等，失败只影响后台触发）
  unawaited(Scheduler.init());
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
