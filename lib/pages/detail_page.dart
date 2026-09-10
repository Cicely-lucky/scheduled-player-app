import 'package:flutter/material.dart';

import '../models/task.dart';
import '../scheduler/scheduler.dart';
import '../storage/task_store.dart';
import '../widgets/player_page.dart';
import 'create_page.dart';

/// 任务详情页
class DetailPage extends StatefulWidget {
  final PlayTask task;
  const DetailPage({super.key, required this.task});

  @override
  State<DetailPage> createState() => _DetailPageState();
}

class _DetailPageState extends State<DetailPage> {
  late PlayTask _t = widget.task;

  Future<void> _delete() async {
    final tasks = await TaskStore.load();
    tasks.removeWhere((e) => e.id == _t.id);
    await TaskStore.save(tasks);
    await Scheduler.scheduleNext();
    if (mounted) Navigator.pop(context);
  }

  Future<void> _edit() async {
    final updated = await Navigator.push<PlayTask>(
      context,
      MaterialPageRoute(builder: (_) => CreatePage(initial: _t)),
    );
    if (updated == null) return;
    final tasks = await TaskStore.load();
    final idx = tasks.indexWhere((e) => e.id == _t.id);
    if (idx >= 0) {
      tasks[idx] = updated;
      await TaskStore.save(tasks);
    }
    await Scheduler.scheduleNext();
    if (mounted) setState(() => _t = updated);
  }

  void _confirmDelete() {
    showDialog<void>(
      context: context,
      builder: (ctx) => AlertDialog(
        title: const Text('删除任务'),
        content: Text('确定删除「${_t.name}」吗？'),
        actions: [
          TextButton(onPressed: () => Navigator.pop(ctx), child: const Text('取消')),
          FilledButton(
            style: FilledButton.styleFrom(backgroundColor: const Color(0xFFEF4444)),
            onPressed: () {
              Navigator.pop(ctx);
              _delete();
            },
            child: const Text('删除'),
          ),
        ],
      ),
    );
  }

  void _play() {
    Navigator.push(
      context,
      MaterialPageRoute(builder: (_) => PlayerPage(task: _t)),
    );
  }

  String _sourceText() {
    if (_t.ct == 'url') {
      try {
        final uri = Uri.parse(_t.url);
        final host = uri.host.replaceFirst('www.', '');
        return host.isNotEmpty ? host : _t.url;
      } catch (_) {
        return _t.url;
      }
    }
    return _t.fileName.split('/').last;
  }

  String _triggerText() {
    final at = _t.nextRunAt(DateTime.now());
    if (at == null) return _t.time;
    final now = DateTime.now();
    final isToday = at.year == now.year && at.month == now.month && at.day == now.day;
    if (isToday) return '今天 ${_t.time}';
    if (at.difference(now).inDays == 1) return '明天 ${_t.time}';
    return '${at.month}月${at.day}日 ${_t.time}';
  }

  String _loopText() {
    if (_t.auto == 'loop') return '循环 ${_t.loop} 遍';
    return '播放 ${_t.dur} 分钟';
  }

  @override
  Widget build(BuildContext context) {
    return Scaffold(
      backgroundColor: const Color(0xFFE8F6FC),
      appBar: AppBar(
        backgroundColor: const Color(0xFFE8F6FC),
        elevation: 0,
        leading: IconButton(
          icon: const Icon(Icons.arrow_back_ios_new, color: Color(0xFF0F172A)),
          onPressed: () => Navigator.pop(context),
        ),
        centerTitle: true,
        title: const Text(
          '任务详情',
          style: TextStyle(fontSize: 17, fontWeight: FontWeight.w700, color: Color(0xFF0F172A)),
        ),
        actions: [
          IconButton(
            icon: const Icon(Icons.edit_outlined, color: Color(0xFF0F172A)),
            onPressed: _edit,
          ),
          const SizedBox(width: 4),
        ],
      ),
      body: ListView(
        padding: const EdgeInsets.fromLTRB(20, 8, 20, 110),
        children: [
          _buildHeroCard(),
          const SizedBox(height: 22),
          const Padding(
            padding: EdgeInsets.only(left: 4, bottom: 10),
            child: Text(
              '任务设置',
              style: TextStyle(fontSize: 14, fontWeight: FontWeight.w700, color: Color(0xFF64748B)),
            ),
          ),
          _settingCard(Icons.access_time_outlined, '触发时间', _triggerText()),
          _settingCard(Icons.calendar_today_outlined, '重复频次', _t.freqDesc),
          _settingCard(Icons.link_outlined, '素材来源', _sourceText()),
          _settingCard(Icons.repeat_outlined, '循环设置', _loopText()),
          const SizedBox(height: 16),
          _buildEnableCard(),
        ],
      ),
      bottomNavigationBar: SafeArea(
        child: Container(
          padding: const EdgeInsets.fromLTRB(20, 12, 20, 16),
          decoration: const BoxDecoration(
            color: Color(0xFFE8F6FC),
          ),
          child: Row(
            children: [
              Expanded(
                child: _bottomBtn(
                  label: '删除',
                  icon: Icons.delete_outline,
                  foreground: const Color(0xFFEF4444),
                  background: Colors.white,
                  border: const Color(0xFFEF4444),
                  onTap: _confirmDelete,
                ),
              ),
              const SizedBox(width: 10),
              Expanded(
                child: _bottomBtn(
                  label: '编辑',
                  icon: Icons.edit_outlined,
                  foreground: const Color(0xFF0F172A),
                  background: Colors.white,
                  border: const Color(0xFFCBD5E1),
                  onTap: _edit,
                ),
              ),
              const SizedBox(width: 10),
              Expanded(
                flex: 2,
                child: _bottomBtn(
                  label: '立即播放',
                  icon: Icons.play_arrow,
                  foreground: Colors.white,
                  background: const Color(0xFF0F172A),
                  border: const Color(0xFF0F172A),
                  onTap: _play,
                ),
              ),
            ],
          ),
        ),
      ),
    );
  }

  Widget _buildHeroCard() {
    final display = _t.ct == 'url'
        ? (_t.url.contains('Unit') ? '新概念二 · Unit 4' : _sourceText())
        : _sourceText();

    return Container(
      padding: const EdgeInsets.fromLTRB(20, 18, 20, 18),
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
      child: Row(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          Container(
            width: 56,
            height: 56,
            decoration: BoxDecoration(
              color: const Color(0xFF7DD3FC),
              borderRadius: BorderRadius.circular(18),
              boxShadow: [
                BoxShadow(
                  color: Color(0xFF38BDF8).withOpacity(0.2),
                  blurRadius: 12,
                  offset: const Offset(0, 4),
                ),
              ],
            ),
            child: ClipRRect(
              borderRadius: BorderRadius.circular(18),
              child: Image.asset('assets/logo/logo_1024.png', fit: BoxFit.cover),
            ),
          ),
          const SizedBox(width: 16),
          Expanded(
            child: Column(
              crossAxisAlignment: CrossAxisAlignment.start,
              children: [
                Container(
                  padding: const EdgeInsets.symmetric(horizontal: 8, vertical: 3),
                  decoration: BoxDecoration(
                    color: const Color(0xFFE0F2FE),
                    borderRadius: BorderRadius.circular(10),
                  ),
                  child: Text(
                    _t.ct == 'url' ? '音频播放' : (_t.isVideo ? '视频播放' : '音频播放'),
                    style: const TextStyle(fontSize: 11, fontWeight: FontWeight.w700, color: Color(0xFF0284C7)),
                  ),
                ),
                const SizedBox(height: 8),
                Text(
                  _t.name,
                  style: const TextStyle(fontSize: 22, fontWeight: FontWeight.w800, color: Color(0xFF0F172A)),
                ),
                const SizedBox(height: 2),
                Text(
                  _t.auto == 'time' ? '${_t.dur} 分钟' : '循环 ${_t.loop} 次',
                  style: const TextStyle(fontSize: 20, fontWeight: FontWeight.w700, color: Color(0xFF38BDF8)),
                ),
                const SizedBox(height: 4),
                Text(
                  '播放${_t.ct == 'url' ? display : (_t.isVideo ? '视频' : '音频')}',
                  style: TextStyle(fontSize: 12, color: Color(0xFF0F172A).withOpacity(0.5)),
                ),
              ],
            ),
          ),
        ],
      ),
    );
  }

  Widget _settingCard(IconData icon, String label, String value) {
    return Container(
      margin: const EdgeInsets.only(bottom: 10),
      padding: const EdgeInsets.symmetric(horizontal: 16, vertical: 14),
      decoration: BoxDecoration(
        color: Colors.white,
        borderRadius: BorderRadius.circular(18),
      ),
      child: Row(
        children: [
          Icon(icon, size: 20, color: const Color(0xFF94A3B8)),
          const SizedBox(width: 14),
          Text(
            label,
            style: TextStyle(fontSize: 14, color: Color(0xFF0F172A).withOpacity(0.65)),
          ),
          const Spacer(),
          Flexible(
            child: Text(
              value,
              textAlign: TextAlign.right,
              style: const TextStyle(fontSize: 14, fontWeight: FontWeight.w700, color: Color(0xFF0F172A)),
              overflow: TextOverflow.ellipsis,
            ),
          ),
        ],
      ),
    );
  }

  Widget _buildEnableCard() {
    return Container(
      padding: const EdgeInsets.symmetric(horizontal: 16, vertical: 12),
      decoration: BoxDecoration(
        color: Colors.white,
        borderRadius: BorderRadius.circular(18),
      ),
      child: Row(
        children: [
          Expanded(
            child: Column(
              crossAxisAlignment: CrossAxisAlignment.start,
              children: [
                const Text(
                  '启用任务',
                  style: TextStyle(fontSize: 15, fontWeight: FontWeight.w700, color: Color(0xFF0F172A)),
                ),
                const SizedBox(height: 3),
                Text(
                  '到点自动唤醒播放',
                  style: TextStyle(fontSize: 12, color: Color(0xFF0F172A).withOpacity(0.5)),
                ),
              ],
            ),
          ),
          Switch(
            value: _t.enabled,
            onChanged: (v) async {
              setState(() => _t.enabled = v);
              final tasks = await TaskStore.load();
              final idx = tasks.indexWhere((e) => e.id == _t.id);
              if (idx >= 0) {
                tasks[idx].enabled = v;
                await TaskStore.save(tasks);
              }
              await Scheduler.scheduleNext();
            },
            activeColor: const Color(0xFF38BDF8),
            activeTrackColor: Color(0xFF38BDF8).withOpacity(0.35),
          ),
        ],
      ),
    );
  }

  Widget _bottomBtn({
    required String label,
    required IconData icon,
    required Color foreground,
    required Color background,
    required Color border,
    required VoidCallback onTap,
  }) {
    return GestureDetector(
      onTap: onTap,
      child: Container(
        height: 50,
        decoration: BoxDecoration(
          color: background,
          borderRadius: BorderRadius.circular(24),
          border: Border.all(color: border),
        ),
        child: Row(
          mainAxisAlignment: MainAxisAlignment.center,
          children: [
            Icon(icon, size: 18, color: foreground),
            const SizedBox(width: 6),
            Text(
              label,
              style: TextStyle(fontSize: 14, fontWeight: FontWeight.w700, color: foreground),
            ),
          ],
        ),
      ),
    );
  }
}
