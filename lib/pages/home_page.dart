import 'package:flutter/material.dart';

import '../models/task.dart';
import '../scheduler/scheduler.dart';
import '../services/perm.dart';
import '../storage/task_store.dart';
import 'create_page.dart';
import 'detail_page.dart';

/// 首页：任务列表 + 下一个任务提示 + 新建入口
class HomePage extends StatefulWidget {
  const HomePage({super.key});

  @override
  State<HomePage> createState() => _HomePageState();
}

class _HomePageState extends State<HomePage> with WidgetsBindingObserver {
  List<PlayTask> _tasks = [];
  bool _loading = true;

  /// 悬浮窗权限状态：null=未检测；false=未授权（显示警告横幅）
  bool? _overlayOk;

  @override
  void initState() {
    super.initState();
    WidgetsBinding.instance.addObserver(this);
    _reload();
    _checkPerm();
    // 通知自检在启动后约 3 秒完成，延迟刷新让首页状态行显示结果
    Future.delayed(const Duration(seconds: 5), () {
      if (mounted) setState(() {});
    });
  }

  @override
  void dispose() {
    WidgetsBinding.instance.removeObserver(this);
    super.dispose();
  }

  /// 从设置页返回时重新检测权限（授权后横幅自动消失）
  @override
  void didChangeAppLifecycleState(AppLifecycleState state) {
    if (state == AppLifecycleState.resumed) _checkPerm();
  }

  Future<void> _checkPerm() async {
    final ok = await Perm.canDrawOverlays();
    if (mounted && ok != _overlayOk) setState(() => _overlayOk = ok);
  }

  /// 打开悬浮窗授权页；打不开则回退应用详情页
  Future<void> _goPermSettings() async {
    final opened = await Perm.requestOverlay();
    if (!opened) await Perm.openAppDetails();
  }

  Future<void> _reload() async {
    final tasks = await TaskStore.load();
    // 任务有任何变化（增删改/启停）后重新计算下一次触发并精确调度
    await Scheduler.scheduleNext();
    if (mounted) {
      setState(() {
        _tasks = tasks;
        _loading = false;
      });
    }
    // 消费"由全屏通知拉起"的待执行任务（此时 _tasks 已就绪）
    _consumePending();
  }

  /// 保存任务并立即重新注册闹钟。
  /// 关键修复：此前新建/启停/删除后未重新调度，
  /// 闹钟从未注册到系统，导致"设置的任务不生效"。
  Future<void> _saveAndReschedule() async {
    await TaskStore.save(_tasks);
    await Scheduler.scheduleNext();
    if (mounted) setState(() {});
  }

  /// 消费"由全屏通知拉起"的待执行任务（统一走前台执行入口）
  void _consumePending() {
    final id = Scheduler.pendingTaskId;
    if (id == null) return;
    Scheduler.pendingTaskId = null;
    Scheduler.executeTaskById(id);
  }

  Future<void> _toggleEnabled(PlayTask t, bool v) async {
    t.enabled = v;
    await _saveAndReschedule();
  }

  Future<void> _delete(PlayTask t) async {
    _tasks.removeWhere((e) => e.id == t.id);
    await _saveAndReschedule();
  }

  /// 计算最近一个将触发的任务
  PlayTask? _nextTask() {
    final now = DateTime.now();
    PlayTask? best;
    DateTime? bestAt;
    for (final t in _tasks) {
      final at = t.nextRunAt(now);
      if (at == null) continue;
      if (bestAt == null || at.isBefore(bestAt)) {
        best = t;
        bestAt = at;
      }
    }
    return best;
  }

  String _nextDesc(PlayTask t) {
    final at = t.nextRunAt(DateTime.now());
    if (at == null) return '暂无可执行任务';
    final now = DateTime.now();
    final diff = at.difference(now);
    final hours = diff.inHours;
    final mins = diff.inMinutes % 60;
    final when = hours > 0 ? '$hours 小时 $mins 分' : '$mins 分';
    return '${t.name} · ${at.month}月${at.day}日 ${t.time} 触发（还有 $when）';
  }

  @override
  Widget build(BuildContext context) {
    return Scaffold(
      appBar: AppBar(
        title: const Text('定时播放'),
        centerTitle: false,
        actions: [
          IconButton(
            icon: const Icon(Icons.refresh),
            tooltip: '刷新',
            onPressed: _reload,
          ),
        ],
      ),
      floatingActionButton: FloatingActionButton.extended(
        onPressed: () async {
          // 接收新建页返回的任务并持久化（CreatePage 只负责表单，不写存储）
          final task = await Navigator.push<PlayTask>(
              context, MaterialPageRoute(builder: (_) => const CreatePage()));
          if (task != null) {
            _tasks.add(task);
            // 关键：保存后立即注册闹钟（此前缺失，任务保存了但闹钟从未生效）
            await _saveAndReschedule();
          }
        },
        icon: const Icon(Icons.add),
        label: const Text('新建任务'),
      ),
      body: _loading
          ? const Center(child: CircularProgressIndicator())
          : _tasks.isEmpty
              ? RefreshIndicator(
                  onRefresh: _reload,
                  child: ListView(
                    padding: const EdgeInsets.fromLTRB(14, 6, 14, 90),
                    children: [
                      if (_overlayOk == false) _buildPermBanner(),
                      const SizedBox(height: 10),
                      _buildEmptyPlaceholder(),
                    ],
                  ),
                )
              : RefreshIndicator(
                  onRefresh: _reload,
                  child: ListView(
                    padding: const EdgeInsets.fromLTRB(14, 6, 14, 90),
                    children: [
                      if (_overlayOk == false) _buildPermBanner(),
                      const SizedBox(height: 10),
                      _buildNextCard(),
                      const SizedBox(height: 10),
                      ..._tasks.map((t) => _buildTaskCard(t)),
                    ],
                  ),
                ),
    );
  }

  /// 空列表占位（用于嵌入 ListView，保留下拉刷新）
  Widget _buildEmptyPlaceholder() {
    return Padding(
      padding: const EdgeInsets.only(top: 120),
      child: Column(
        children: [
          Icon(Icons.alarm, size: 64, color: Colors.grey.shade300),
          const SizedBox(height: 12),
          Text('还没有任务', style: TextStyle(color: Colors.grey.shade500)),
          const SizedBox(height: 4),
          Text('点击右下角 + 新建第一个定时任务',
              style: TextStyle(color: Colors.grey.shade400, fontSize: 13)),
        ],
      ),
    );
  }

  /// 悬浮窗权限警告横幅：重装App后权限会重置，未授权时到点无法
  /// 后台打开链接，此处引导用户一键跳转设置页自服务授权
  Widget _buildPermBanner() {
    return Card(
      elevation: 0,
      color: Colors.orange.withOpacity(0.1),
      shape: RoundedRectangleBorder(
        borderRadius: BorderRadius.circular(14),
        side: BorderSide(color: Colors.orange.withOpacity(0.4)),
      ),
      child: Padding(
        padding: const EdgeInsets.all(14),
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            Row(
              children: [
                const Icon(Icons.warning_amber_rounded,
                    color: Colors.deepOrange),
                const SizedBox(width: 8),
                const Expanded(
                  child: Text(
                    '悬浮窗权限未开启',
                    style: TextStyle(
                        fontSize: 14,
                        fontWeight: FontWeight.w600,
                        color: Colors.deepOrange),
                  ),
                ),
                TextButton(
                  onPressed: _goPermSettings,
                  style: TextButton.styleFrom(
                    backgroundColor: Colors.deepOrange,
                    foregroundColor: Colors.white,
                    padding: const EdgeInsets.symmetric(horizontal: 14),
                    minimumSize: const Size(0, 32),
                  ),
                  child: const Text('去开启', style: TextStyle(fontSize: 13)),
                ),
              ],
            ),
            const SizedBox(height: 6),
            Text(
              '锁屏或后台时到点将无法自动打开链接（B站等）。\n'
              '小米手机请同时在 应用信息 → 权限管理 中开启\n'
              '「后台弹出界面」和「锁屏显示」。',
              style: TextStyle(
                  fontSize: 12, height: 1.5, color: Colors.grey.shade700),
            ),
          ],
        ),
      ),
    );
  }

  Widget _buildNextCard() {
    final next = _nextTask();
    return Card(
      elevation: 0,
      color: Colors.indigo.withOpacity(0.08),
      shape: RoundedRectangleBorder(
        borderRadius: BorderRadius.circular(14),
        side: BorderSide(color: Colors.indigo.withOpacity(0.25)),
      ),
      child: Padding(
        padding: const EdgeInsets.all(14),
        child: Row(
          children: [
            const Icon(Icons.bolt, color: Colors.indigo),
            const SizedBox(width: 10),
            Expanded(
              child: Column(
                crossAxisAlignment: CrossAxisAlignment.start,
                children: [
                  Text('下一个任务',
                      style: TextStyle(
                          fontSize: 12,
                          color: Colors.indigo.shade400,
                          fontWeight: FontWeight.w600)),
                  const SizedBox(height: 3),
                  Text(
                    next == null ? '暂无排程' : _nextDesc(next),
                    style: const TextStyle(fontSize: 13.5, color: Colors.black87),
                  ),
                  const SizedBox(height: 3),
                  // 通知链路自检结果：绿色=通过；红色=失败（附原因），
                  // 失败说明"到点弹不出提醒"的断点在通知环节
                  if (Scheduler.notifSelfTest.isNotEmpty)
                    Row(
                      children: [
                        Icon(
                          Scheduler.notifSelfTest.contains('失败')
                              ? Icons.error_outline
                              : Icons.check_circle_outline,
                          size: 12,
                          color: Scheduler.notifSelfTest.contains('失败')
                              ? Colors.red.shade400
                              : Colors.green.shade600,
                        ),
                        const SizedBox(width: 4),
                        Expanded(
                          child: Text(
                            Scheduler.notifSelfTest,
                            style: TextStyle(
                                fontSize: 11.5,
                                color: Scheduler.notifSelfTest.contains('失败')
                                    ? Colors.red.shade400
                                    : Colors.green.shade600),
                            overflow: TextOverflow.ellipsis,
                          ),
                        ),
                      ],
                    ),
                  const SizedBox(height: 3),
                  // 调度状态：直接显示闹钟注册结果（成功/失败及原因）
                  Row(
                    children: [
                      Icon(Icons.schedule, size: 12, color: Colors.indigo.shade300),
                      const SizedBox(width: 4),
                      Expanded(
                        child: Text(
                          Scheduler.status,
                          style: TextStyle(
                              fontSize: 11.5, color: Colors.indigo.shade300),
                          overflow: TextOverflow.ellipsis,
                        ),
                      ),
                    ],
                  ),
                ],
              ),
            ),
          ],
        ),
      ),
    );
  }

  Widget _buildTaskCard(PlayTask t) {
    return Card(
      elevation: 0,
      margin: const EdgeInsets.only(bottom: 10),
      shape: RoundedRectangleBorder(
        borderRadius: BorderRadius.circular(14),
        side: BorderSide(color: Colors.grey.shade200),
      ),
      child: InkWell(
        borderRadius: BorderRadius.circular(14),
        onTap: () async {
          await Navigator.push(context,
              MaterialPageRoute(builder: (_) => DetailPage(task: t)));
          _reload();
        },
        child: Padding(
          padding: const EdgeInsets.fromLTRB(14, 12, 6, 12),
          child: Row(
            crossAxisAlignment: CrossAxisAlignment.start,
            children: [
              Container(
                width: 42,
                height: 42,
                decoration: BoxDecoration(
                  color: t.enabled
                      ? Colors.indigo.withOpacity(0.1)
                      : Colors.grey.shade200,
                  borderRadius: BorderRadius.circular(12),
                ),
                child: Icon(
                  t.ct == 'url' ? Icons.public : Icons.music_note,
                  color: t.enabled ? Colors.indigo : Colors.grey,
                  size: 22,
                ),
              ),
              const SizedBox(width: 12),
              Expanded(
                child: Column(
                  crossAxisAlignment: CrossAxisAlignment.start,
                  children: [
                    Row(
                      children: [
                        Expanded(
                          child: Text(
                            t.name,
                            style: TextStyle(
                              fontSize: 15,
                              fontWeight: FontWeight.w600,
                              color: t.enabled ? Colors.black87 : Colors.grey,
                            ),
                            overflow: TextOverflow.ellipsis,
                          ),
                        ),
                        Opacity(
                          opacity: t.enabled ? 1 : 0.5,
                          child: Text(
                            t.time,
                            style: const TextStyle(
                                fontSize: 15,
                                fontWeight: FontWeight.w700,
                                color: Colors.indigo),
                          ),
                        ),
                      ],
                    ),
                    const SizedBox(height: 4),
                    Text(
                      '${t.freqDesc} · ${t.contentDesc}',
                      style: TextStyle(
                          fontSize: 12.5, color: Colors.grey.shade600),
                    ),
                    const SizedBox(height: 2),
                    Text(
                      t.stopDesc + (t.mute ? ' · 静音提醒' : ''),
                      style: TextStyle(
                          fontSize: 12, color: Colors.grey.shade500),
                    ),
                  ],
                ),
              ),
              Switch(
                value: t.enabled,
                onChanged: (v) => _toggleEnabled(t, v),
              ),
            ],
          ),
        ),
      ),
    );
  }
}
