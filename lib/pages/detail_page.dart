import 'package:flutter/material.dart';

import '../models/task.dart';
import '../storage/task_store.dart';
import '../widgets/player_page.dart';
import 'create_page.dart';

/// 任务详情页：汇总所有配置，支持编辑、立即播放与删除
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
    if (mounted) Navigator.pop(context);
  }

  /// 进入编辑页，保存后原地更新详情与存储（保留原 id）
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
            style: FilledButton.styleFrom(backgroundColor: Colors.red),
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

  @override
  Widget build(BuildContext context) {
    return Scaffold(
      appBar: AppBar(title: const Text('任务详情')),
      body: ListView(
        padding: const EdgeInsets.fromLTRB(14, 8, 14, 100),
        children: [
          Card(
            elevation: 0,
            shape: RoundedRectangleBorder(
              borderRadius: BorderRadius.circular(14),
              side: BorderSide(color: Colors.grey.shade200),
            ),
            child: Padding(
              padding: const EdgeInsets.all(18),
              child: Column(
                children: [
                  Container(
                    width: 54,
                    height: 54,
                    decoration: BoxDecoration(
                      color: Colors.indigo.withOpacity(0.1),
                      borderRadius: BorderRadius.circular(16),
                    ),
                    child: Icon(
                      _t.ct == 'url' ? Icons.public : Icons.music_note,
                      color: Colors.indigo,
                      size: 28,
                    ),
                  ),
                  const SizedBox(height: 10),
                  Text(_t.name,
                      style: const TextStyle(
                          fontSize: 18, fontWeight: FontWeight.w700)),
                  const SizedBox(height: 4),
                  Text(_t.freqDesc,
                      style:
                          TextStyle(fontSize: 13, color: Colors.grey.shade600)),
                ],
              ),
            ),
          ),
          const SizedBox(height: 10),
          _row(Icons.schedule, '触发时间', _t.time,
              enabled: _t.enabled,
              onChanged: null),
          _row(Icons.calendar_today, '频次', _t.freqDesc),
          if (_t.freq == 'date') _row(Icons.event, '日期', _t.date),
          _row(
            Icons.link,
            '内容',
            _t.ct == 'url' ? _t.url : (_t.isVideo ? '视频文件' : '音频文件'),
          ),
          _row(Icons.stop_circle_outlined, '自动停止', _t.autoDesc),
          _row(
            Icons.lock_outline,
            '锁定设置',
            _t.lockEnabled ? '前 ${_t.lock} 分钟不可停，到点出现关闭按钮' : '未启用',
            accent: _t.lockEnabled ? Colors.amber.shade800 : null,
          ),
          SwitchListTile(
            contentPadding: const EdgeInsets.symmetric(horizontal: 14),
            title: const Text('启用任务', style: TextStyle(fontWeight: FontWeight.w600)),
            value: _t.enabled,
            onChanged: (v) async {
              setState(() => _t.enabled = v);
              final tasks = await TaskStore.load();
              final idx = tasks.indexWhere((e) => e.id == _t.id);
              if (idx >= 0) {
                tasks[idx].enabled = v;
                await TaskStore.save(tasks);
              }
            },
          ),
        ],
      ),
      bottomNavigationBar: SafeArea(
        child: Padding(
          padding: const EdgeInsets.all(14),
          child: Row(
            children: [
              Expanded(
                child: OutlinedButton.icon(
                  onPressed: _confirmDelete,
                  style: OutlinedButton.styleFrom(
                    minimumSize: const Size.fromHeight(46),
                    foregroundColor: Colors.red,
                    side: const BorderSide(color: Colors.red),
                  ),
                  icon: const Icon(Icons.delete_outline, size: 18),
                  label: const Text('删除'),
                ),
              ),
              const SizedBox(width: 10),
              Expanded(
                child: OutlinedButton.icon(
                  onPressed: _edit,
                  style: OutlinedButton.styleFrom(
                      minimumSize: const Size.fromHeight(46)),
                  icon: const Icon(Icons.edit_outlined, size: 18),
                  label: const Text('编辑'),
                ),
              ),
              const SizedBox(width: 10),
              Expanded(
                child: FilledButton.icon(
                  onPressed: _play,
                  style: FilledButton.styleFrom(
                      minimumSize: const Size.fromHeight(46)),
                  icon: const Icon(Icons.play_arrow, size: 20),
                  label: const Text('立即播放'),
                ),
              ),
            ],
          ),
        ),
      ),
    );
  }

  Widget _row(IconData icon, String label, String value,
      {bool enabled = true, Color? accent, void Function(bool)? onChanged}) {
    return Card(
      elevation: 0,
      margin: const EdgeInsets.only(bottom: 8),
      shape: RoundedRectangleBorder(
        borderRadius: BorderRadius.circular(12),
        side: BorderSide(color: Colors.grey.shade200),
      ),
      child: Padding(
        padding: const EdgeInsets.symmetric(horizontal: 14, vertical: 13),
        child: Row(
          children: [
            Icon(icon, size: 18, color: accent ?? Colors.grey.shade600),
            const SizedBox(width: 10),
            Text(label,
                style: TextStyle(
                    fontSize: 13, color: Colors.grey.shade600)),
            const Spacer(),
            Flexible(
              child: Text(
                value,
                textAlign: TextAlign.right,
                style: TextStyle(
                  fontSize: 13.5,
                  fontWeight: FontWeight.w600,
                  color: accent ?? Colors.black87,
                ),
                overflow: TextOverflow.ellipsis,
              ),
            ),
          ],
        ),
      ),
    );
  }
}
