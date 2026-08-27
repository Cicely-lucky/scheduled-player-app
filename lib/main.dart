import 'package:flutter/material.dart';
import 'package:workmanager/workmanager.dart';

import 'pages/home_page.dart';
import 'scheduler/scheduler.dart';

/// workmanager 后台回调入口（必须为顶层函数）
@pragma('vm:entry-point')
void callbackDispatcher() {
  Workmanager().executeTask((task, inputData) async {
    await Scheduler.runCheck();
    return true;
  });
}

Future<void> main() async {
  WidgetsFlutterBinding.ensureInitialized();

  // 先启动界面，避免后台调度初始化失败导致 App 闪退
  runApp(const ScheduledPlayerApp());

  // 后台任务初始化：每 15 分钟检查一次是否有到期任务
  // 初始化失败只影响后台定时触发，不影响 App 正常打开使用
  try {
    await Workmanager().initialize(callbackDispatcher);
    await Workmanager().registerPeriodicTask(
      'scheduled-player-check',
      'checkTasks',
      frequency: const Duration(minutes: 15),
    );
  } catch (e) {
    debugPrint('后台调度初始化失败（不影响主界面）: $e');
  }
}

class ScheduledPlayerApp extends StatelessWidget {
  const ScheduledPlayerApp({super.key});

  @override
  Widget build(BuildContext context) {
    return MaterialApp(
      title: '定时播放',
      debugShowCheckedModeBanner: false,
      theme: ThemeData(
        colorScheme: ColorScheme.fromSeed(seedColor: Colors.indigo),
        useMaterial3: true,
        scaffoldBackgroundColor: const Color(0xFFF7F7FA),
      ),
      home: const HomePage(),
    );
  }
}
