import 'package:flutter/material.dart';

import '../models/task.dart';
import '../scheduler/scheduler.dart';
import '../services/perm.dart';
import '../storage/task_store.dart';
import 'create_page.dart';
import 'detail_page.dart';

/// 小云闹钟首页
class HomePage extends StatefulWidget {
  const HomePage({super.key});

  @override
  State<HomePage> createState() => _HomePageState();
}

class _HomePageState extends State<HomePage> with WidgetsBindingObserver {
  List<PlayTask> _tasks = [];
  bool _loading = true;
  bool? _overlayOk;

  @override
  void initState() {
    super.initState();
    WidgetsBinding.instance.addObserver(this);
    _reload();
    _checkPerm();
    Future.delayed(const Duration(seconds: 5), () {
      if (mounted) setState(() {});
    });
  }

  @override
  void dispose() {
    WidgetsBinding.instance.removeObserver(this);
    super.dispose();
  }

  @override
  void didChangeAppLifecycleState(AppLifecycleState state) {
    if (state == AppLifecycleState.resumed) _checkPerm();
  }

  Future<void> _checkPerm() async {
    final ok = await Perm.canDrawOverlays();
    if (mounted && ok != _overlayOk) setState(() => _overlayOk = ok);
  }

  Future<void> _goPermSettings() async {
    final opened = await Perm.requestOverlay();
    if (!opened) await Perm.openAppDetails();
  }

  Future<void> _reload() async {
    final tasks = await TaskStore.load();
    await Scheduler.scheduleNext();
    if (mounted) {
      setState(() {
        _tasks = tasks;
        _loading = false;
      });
    }
    _consumePending();
  }

  Future<void> _saveAndReschedule() async {
    await TaskStore.save(_tasks);
    await Scheduler.scheduleNext();
    if (mounted) setState(() {});
  }

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

  String _untilText(DateTime at) {
    final diff = at.difference(DateTime.now());
    final h = diff.inHours.clamp(0, 99);
    final m = diff.inMinutes.remainder(60);
    return '${h.toString().padLeft(2, '0')}:${m.toString().padLeft(2, '0')}';
  }

  @override
  Widget build(BuildContext context) {
    return Scaffold(
      backgroundColor: const Color(0xFFE8F6FC),
      body: _loading
          ? const Center(child: CircularProgressIndicator(color: Color(0xFF38BDF8)))
          : RefreshIndicator(
              onRefresh: _reload,
              color: const Color(0xFF38BDF8),
              backgroundColor: Colors.white,
              child: ListView(
                padding: const EdgeInsets.fromLTRB(20, 16, 20, 110),
                children: [
                  const SizedBox(height: 8),
                  _buildHeader(),
                  const SizedBox(height: 18),
                  if (_overlayOk == false) ...[
                    _buildPermBanner(),
                    const SizedBox(height: 14),
                  ],
                  _buildNextCard(),
                  const SizedBox(height: 22),
                  _buildListHeader(),
                  const SizedBox(height: 12),
                  ..._tasks.asMap().entries.map((e) => _buildTaskCard(e.key + 1, e.value)),
                  if (_tasks.isEmpty) _buildEmptyPlaceholder(),
                ],
              ),
            ),
      floatingActionButton: Padding(
        padding: const EdgeInsets.symmetric(horizontal: 20),
        child: SizedBox(
          width: double.infinity,
          child: FloatingActionButton.extended(
            elevation: 0,
            highlightElevation: 0,
            backgroundColor: const Color(0xFF0F172A),
            shape: RoundedRectangleBorder(borderRadius: BorderRadius.circular(28)),
            onPressed: () async {
              final task = await Navigator.push<PlayTask>(
                context,
                MaterialPageRoute(builder: (_) => const CreatePage()),
              );
              if (task != null) {
                _tasks.add(task);
                await _saveAndReschedule();
              }
            },
            icon: const Icon(Icons.add, color: Colors.white),
            label: const Text('新建任务', style: TextStyle(color: Colors.white, fontSize: 15)),
          ),
        ),
      ),
      floatingActionButtonLocation: FloatingActionButtonLocation.centerFloat,
    );
  }

  Widget _buildHeader() {
    return Row(
      crossAxisAlignment: CrossAxisAlignment.center,
      children: [
        Container(
          width: 46,
          height: 46,
          decoration: BoxDecoration(
            color: const Color(0xFF7DD3FC),
            borderRadius: BorderRadius.circular(14),
            boxShadow: [
              BoxShadow(
                color: Color(0xFF38BDF8).withOpacity(0.25),
                blurRadius: 12,
                offset: const Offset(0, 4),
              ),
            ],
          ),
          child: ClipRRect(
            borderRadius: BorderRadius.circular(14),
            child: Image.asset('assets/logo/logo_1024.png', fit: BoxFit.cover),
          ),
        ),
        const SizedBox(width: 12),
        Expanded(
          child: Column(
            crossAxisAlignment: CrossAxisAlignment.start,
            children: [
              const Text(
                '小云闹钟',
                style: TextStyle(
                  fontSize: 18,
                  fontWeight: FontWeight.w800,
                  color: Color(0xFF0F172A),
                ),
              ),
              const SizedBox(height: 2),
              Text(
                '学习搭子 · 准时唤醒你',
                style: TextStyle(
                  fontSize: 12,
                  color: Color(0xFF0F172A).withOpacity(0.45),
                ),
              ),
            ],
          ),
        ),
        Row(
          mainAxisSize: MainAxisSize.min,
          children: [
            Text(
              '自动播放',
              style: TextStyle(
                fontSize: 12,
                fontWeight: FontWeight.w600,
                color: Color(0xFF0F172A).withOpacity(0.6),
              ),
            ),
            const SizedBox(width: 6),
            Switch(
              value: true,
              onChanged: (_) {},
              activeColor: const Color(0xFF38BDF8),
              activeTrackColor: Color(0xFF38BDF8).withOpacity(0.35),
            ),
          ],
        ),
      ],
    );
  }

  Widget _buildPermBanner() {
    return Container(
      padding: const EdgeInsets.all(14),
      decoration: BoxDecoration(
        color: const Color(0xFFFFF7ED),
        borderRadius: BorderRadius.circular(16),
        border: Border.all(color: const Color(0xFFFDBA74)),
      ),
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          Row(
            children: [
              const Icon(Icons.warning_amber_rounded, color: Color(0xFFEA580C), size: 18),
              const SizedBox(width: 8),
              const Expanded(
                child: Text(
                  '悬浮窗权限未开启',
                  style: TextStyle(
                    fontSize: 13,
                    fontWeight: FontWeight.w700,
                    color: Color(0xFFEA580C),
                  ),
                ),
              ),
              GestureDetector(
                onTap: _goPermSettings,
                child: Container(
                  padding: const EdgeInsets.symmetric(horizontal: 12, vertical: 6),
                  decoration: BoxDecoration(
                    color: const Color(0xFFEA580C),
                    borderRadius: BorderRadius.circular(20),
                  ),
                  child: const Text(
                    '去开启',
                    style: TextStyle(color: Colors.white, fontSize: 12, fontWeight: FontWeight.w600),
                  ),
                ),
              ),
            ],
          ),
          const SizedBox(height: 6),
          Text(
            '锁屏或后台时到点将无法自动打开链接。小米手机请同时在应用信息 → 权限管理中开启「后台弹出界面」和「锁屏显示」。',
            style: TextStyle(fontSize: 11, height: 1.5, color: Color(0xFF0F172A).withOpacity(0.55)),
          ),
        ],
      ),
    );
  }

  Widget _buildNextCard() {
    final next = _nextTask();
    if (next == null) {
      return Container(
        padding: const EdgeInsets.all(22),
        decoration: BoxDecoration(
          color: Colors.white,
          borderRadius: BorderRadius.circular(24),
          boxShadow: [
            BoxShadow(color: Color(0xFF38BDF8).withOpacity(0.08), blurRadius: 24, offset: const Offset(0, 8)),
          ],
        ),
        child: const Center(
          child: Text(
            '暂无待执行任务\n点击底部新建一个吧',
            textAlign: TextAlign.center,
            style: TextStyle(fontSize: 14, color: Color(0xFF94A3B8)),
          ),
        ),
      );
    }

    final at = next.nextRunAt(DateTime.now())!;
    final isToday = at.year == DateTime.now().year &&
        at.month == DateTime.now().month &&
        at.day == DateTime.now().day;

    return Container(
      padding: const EdgeInsets.fromLTRB(22, 18, 22, 18),
      decoration: BoxDecoration(
        color: Colors.white,
        borderRadius: BorderRadius.circular(24),
        boxShadow: [
          BoxShadow(
            color: Color(0xFF38BDF8).withOpacity(0.08),
            blurRadius: 24,
            offset: const Offset(0, 8),
          ),
        ],
      ),
      child: Stack(
        children: [
          Positioned(
            right: -30,
            top: -10,
            child: Container(
              width: 110,
              height: 110,
              decoration: BoxDecoration(
                color: const Color(0xFFFECDD3),
                borderRadius: BorderRadius.circular(55),
              ),
            ),
          ),
          Column(
            crossAxisAlignment: CrossAxisAlignment.start,
            children: [
              Row(
                children: [
                  Container(
                    width: 8,
                    height: 8,
                    decoration: const BoxDecoration(color: Color(0xFFFB7185), shape: BoxShape.circle),
                  ),
                  const SizedBox(width: 6),
                  const Text(
                    '下一个任务',
                    style: TextStyle(fontSize: 12, fontWeight: FontWeight.w600, color: Color(0xFF64748B)),
                  ),
                  const Spacer(),
                  Text(
                    isToday ? '今天 ${next.time}' : '${at.month}月${at.day}日 ${next.time}',
                    style: const TextStyle(fontSize: 13, fontWeight: FontWeight.w700, color: Color(0xFF0F172A)),
                  ),
                ],
              ),
              const SizedBox(height: 14),
              Text(
                next.name,
                style: const TextStyle(fontSize: 26, fontWeight: FontWeight.w800, color: Color(0xFF0F172A)),
              ),
              if (next.auto == 'time') ...[
                Text(
                  '${next.dur} 分钟',
                  style: const TextStyle(fontSize: 22, fontWeight: FontWeight.w700, color: Color(0xFF38BDF8)),
                ),
              ] else ...[
                Text(
                  '循环 ${next.loop} 次',
                  style: const TextStyle(fontSize: 22, fontWeight: FontWeight.w700, color: Color(0xFF38BDF8)),
                ),
              ],
              const SizedBox(height: 6),
              Text(
                '${next.contentDesc} · ${next.ct == 'url' ? next.url : next.fileName.split('/').last}',
                maxLines: 1,
                overflow: TextOverflow.ellipsis,
                style: TextStyle(fontSize: 13, color: Color(0xFF0F172A).withOpacity(0.5)),
              ),
              const SizedBox(height: 18),
              Row(
                children: [
                  Text(
                    _untilText(at),
                    style: const TextStyle(fontSize: 32, fontWeight: FontWeight.w800, color: Color(0xFF0F172A)),
                  ),
                  const SizedBox(width: 6),
                  Column(
                    crossAxisAlignment: CrossAxisAlignment.start,
                    children: [
                      Text(
                        '后开始',
                        style: TextStyle(fontSize: 12, color: Color(0xFF0F172A).withOpacity(0.5)),
                      ),
                    ],
                  ),
                  const Spacer(),
                  Container(
                    padding: const EdgeInsets.symmetric(horizontal: 12, vertical: 6),
                    decoration: BoxDecoration(
                      color: const Color(0xFFE0F2FE),
                      borderRadius: BorderRadius.circular(20),
                    ),
                    child: const Text(
                      '待唤醒',
                      style: TextStyle(fontSize: 12, fontWeight: FontWeight.w700, color: Color(0xFF0284C7)),
                    ),
                  ),
                ],
              ),
            ],
          ),
        ],
      ),
    );
  }

  Widget _buildListHeader() {
    return Row(
      children: [
        const Text(
          '任务列表',
          style: TextStyle(fontSize: 16, fontWeight: FontWeight.w800, color: Color(0xFF0F172A)),
        ),
        const Spacer(),
        Text(
          '${_tasks.length} 项',
          style: TextStyle(fontSize: 13, color: Color(0xFF0F172A).withOpacity(0.45)),
        ),
      ],
    );
  }

  Widget _buildTaskCard(int index, PlayTask t) {
    final colors = [
      const Color(0xFF7DD3FC),
      const Color(0xFFFECDD3),
      const Color(0xFFBBF7D0),
      const Color(0xFFFDE68A),
      const Color(0xFFDDD6FE),
    ];
    final bg = colors[(index - 1) % colors.length];

    return GestureDetector(
      onTap: () async {
        await Navigator.push(
          context,
          MaterialPageRoute(builder: (_) => DetailPage(task: t)),
        );
        _reload();
      },
      child: Container(
        margin: const EdgeInsets.only(bottom: 12),
        padding: const EdgeInsets.symmetric(horizontal: 14, vertical: 14),
        decoration: BoxDecoration(
          color: Colors.white,
          borderRadius: BorderRadius.circular(18),
          boxShadow: [
            BoxShadow(color: Color(0xFF38BDF8).withOpacity(0.05), blurRadius: 12, offset: const Offset(0, 4)),
          ],
        ),
        child: Row(
          children: [
            Container(
              width: 44,
              height: 44,
              decoration: BoxDecoration(
                color: bg,
                borderRadius: BorderRadius.circular(14),
              ),
              child: Center(
                child: Text(
                  '$index',
                  style: const TextStyle(fontSize: 16, fontWeight: FontWeight.w800, color: Colors.white),
                ),
              ),
            ),
            const SizedBox(width: 14),
            Expanded(
              child: Column(
                crossAxisAlignment: CrossAxisAlignment.start,
                children: [
                  Text(
                    t.name,
                    style: const TextStyle(fontSize: 15, fontWeight: FontWeight.w700, color: Color(0xFF0F172A)),
                  ),
                  const SizedBox(height: 4),
                  Text(
                    '${t.freqDesc} · ${t.time} · ${t.autoDesc}',
                    style: TextStyle(fontSize: 12, color: Color(0xFF0F172A).withOpacity(0.5)),
                  ),
                ],
              ),
            ),
            Container(
              width: 12,
              height: 12,
              decoration: BoxDecoration(
                color: t.enabled ? const Color(0xFF38BDF8) : const Color(0xFFE2E8F0),
                shape: BoxShape.circle,
              ),
            ),
          ],
        ),
      ),
    );
  }

  Widget _buildEmptyPlaceholder() {
    return Padding(
      padding: const EdgeInsets.only(top: 80),
      child: Column(
        children: [
          Icon(Icons.alarm, size: 64, color: Color(0xFF0F172A).withOpacity(0.1)),
          const SizedBox(height: 12),
          Text('还没有任务', style: TextStyle(color: Color(0xFF0F172A).withOpacity(0.35))),
          const SizedBox(height: 4),
          Text('点击底部 + 新建第一个定时任务',
              style: TextStyle(color: Color(0xFF0F172A).withOpacity(0.25), fontSize: 13)),
        ],
      ),
    );
  }
}
