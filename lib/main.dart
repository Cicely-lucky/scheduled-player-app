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

  // 初始化后台任务：每 15 分钟检查一次是否有到期任务
  await Workmanager().initialize(callbackDispatcher);
  await Workmanager().registerPeriodicTask(
    'scheduled-player-check',
    'checkTasks',
    frequency: const Duration(minutes: 15),
  );

  runApp(const ScheduledPlayerApp());
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
