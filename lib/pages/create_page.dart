import 'package:file_picker/file_picker.dart';
import 'package:flutter/material.dart';

import '../models/task.dart';

/// 新建/编辑任务页：所有选项一次性展示，与 HTML Demo 交互一致
///
/// [initial] 为空时是新建模式；非空时是编辑模式（表单预填，保存保留原 id）
class CreatePage extends StatefulWidget {
  final PlayTask? initial;
  const CreatePage({super.key, this.initial});

  @override
  State<CreatePage> createState() => _CreatePageState();
}

class _CreatePageState extends State<CreatePage> {
  late final TextEditingController _nameCtrl;

  late String _time;
  late String _freq;
  late Set<int> _days;
  late String _date;

  late String _ct;
  late String _url;
  late String _fileName;
  late bool _isVideo;

  late String _auto;
  late int _loop;
  late int _dur;
  late bool _lockEnabled;
  late int _lock;

  bool get _isEdit => widget.initial != null;

  @override
  void initState() {
    super.initState();
    final i = widget.initial;
    _nameCtrl = TextEditingController(text: i?.name ?? '');
    _time = i?.time ?? '08:00';
    _freq = i?.freq ?? 'daily';
    _days = Set.of(i?.days.isNotEmpty == true ? i!.days : const {1, 2, 3, 4, 5});
    _date = i?.date ?? '';
    _ct = i?.ct ?? 'url';
    _url = i?.url ?? '';
    _fileName = i?.fileName ?? '';
    _isVideo = i?.isVideo ?? false;
    _auto = i?.auto ?? 'loop';
    _loop = i?.loop ?? 1;
    _dur = i?.dur ?? 30;
    _lockEnabled = i?.lockEnabled ?? false;
    _lock = i?.lock ?? 10;
  }

  static const _weekNames = ['一', '二', '三', '四', '五', '六', '日'];
  static const _videoExts = ['mp4', 'mov', 'mkv', 'webm', 'm4v'];

  @override
  void dispose() {
    _nameCtrl.dispose();
    super.dispose();
  }

  Future<void> _pickTime() async {
    final parts = _time.split(':');
    final picked = await showTimePicker(
      context: context,
      initialTime: TimeOfDay(hour: int.parse(parts[0]), minute: int.parse(parts[1])),
    );
    if (picked != null) {
      setState(() {
        _time = '${picked.hour.toString().padLeft(2, '0')}:${picked.minute.toString().padLeft(2, '0')}';
      });
    }
  }

  Future<void> _pickDate() async {
    final now = DateTime.now();
    final picked = await showDatePicker(
      context: context,
      initialDate: now,
      firstDate: now,
      lastDate: now.add(const Duration(days: 365 * 2)),
    );
    if (picked != null) {
      setState(() {
        _date = '${picked.year}-${picked.month.toString().padLeft(2, '0')}-${picked.day.toString().padLeft(2, '0')}';
      });
    }
  }

  Future<void> _pickFile() async {
    final result = await FilePicker.platform.pickFiles(
      type: FileType.custom,
      allowedExtensions: ['mp3', 'wav', 'm4a', 'aac', 'ogg', 'flac', 'mp4', 'mov', 'mkv', 'webm'],
    );
    if (result != null && result.files.single.path != null) {
      final f = result.files.single;
      setState(() {
        _fileName = f.path!;
        _isVideo = _videoExts.contains((f.extension ?? '').toLowerCase());
      });
    }
  }

  void _toggleDay(int d) {
    setState(() {
      if (_days.contains(d)) {
        _days.remove(d);
      } else {
        _days.add(d);
      }
    });
  }

  void _adjust(ValueNotifier<int> notifier, int delta) {
    final v = notifier.value + delta;
    notifier.value = v < 1 ? 1 : (v > 999 ? 999 : v);
  }

  void _save() {
    final name = _nameCtrl.text.trim();
    if (name.isEmpty) {
      ScaffoldMessenger.of(context)
          .showSnackBar(const SnackBar(content: Text('请输入任务名称')));
      return;
    }
    if (_freq == 'weekly' && _days.isEmpty) {
      ScaffoldMessenger.of(context)
          .showSnackBar(const SnackBar(content: Text('请至少勾选一个周几')));
      return;
    }
    if (_freq == 'date' && _date.isEmpty) {
      ScaffoldMessenger.of(context)
          .showSnackBar(const SnackBar(content: Text('请选择触发日期')));
      return;
    }
    if (_ct == 'url' && _url.trim().isEmpty) {
      ScaffoldMessenger.of(context)
          .showSnackBar(const SnackBar(content: Text('请输入要打开的网址')));
      return;
    }
    if (_ct == 'file' && _fileName.isEmpty) {
      ScaffoldMessenger.of(context)
          .showSnackBar(const SnackBar(content: Text('请导入音视频文件')));
      return;
    }
    final task = PlayTask(
      // 编辑模式保留原 id（保证启停状态、触发记录不丢），新建用毫秒时间戳
      id: widget.initial?.id ?? DateTime.now().millisecondsSinceEpoch,
      name: name,
      freq: _freq,
      days: Set.of(_days),
      time: _time,
      date: _date,
      ct: _ct,
      url: _url.trim(),
      fileName: _fileName,
      isVideo: _isVideo,
      auto: _auto,
      loop: _loop,
      dur: _dur,
      lockEnabled: _lockEnabled,
      lock: _lock,
    );
    Navigator.pop(context, task);
  }

  @override
  Widget build(BuildContext context) {
    final loopVal = ValueNotifier(_loop);
    final durVal = ValueNotifier(_dur);
    final lockVal = ValueNotifier(_lock);
    loopVal.addListener(() => _loop = loopVal.value);
    durVal.addListener(() => _dur = durVal.value);
    lockVal.addListener(() => _lock = lockVal.value);

    return Scaffold(
      appBar: AppBar(title: Text(_isEdit ? '编辑任务' : '新建任务')),
      body: ListView(
        padding: const EdgeInsets.fromLTRB(14, 8, 14, 100),
        children: [
          _card('任务名称',
              child: TextField(
                controller: _nameCtrl,
                decoration: const InputDecoration(
                  hintText: '如：早读铃声',
                  border: OutlineInputBorder(),
                  isDense: true,
                ),
              )),
          _card('触发时间',
              child: _rowTap(Icons.schedule, '$_time 触发', _pickTime)),
          _card('频次',
              child: Column(
                crossAxisAlignment: CrossAxisAlignment.start,
                children: [
                  Wrap(
                    spacing: 8,
                    children: [
                      _chip('每天', _freq == 'daily', () => setState(() => _freq = 'daily')),
                      _chip('每周', _freq == 'weekly', () => setState(() => _freq = 'weekly')),
                      _chip('特定日期', _freq == 'date', () => setState(() => _freq = 'date')),
                    ],
                  ),
                  if (_freq == 'weekly') ...[
                    const SizedBox(height: 12),
                    Wrap(
                      spacing: 6,
                      children: List.generate(7, (i) {
                        final d = i + 1;
                        return FilterChip(
                          label: Text('周${_weekNames[i]}'),
                          selected: _days.contains(d),
                          onSelected: (_) => _toggleDay(d),
                        );
                      }),
                    ),
                  ],
                  if (_freq == 'date') ...[
                    const SizedBox(height: 10),
                    _rowTap(Icons.event, _date.isEmpty ? '选择日期' : _date, _pickDate),
                  ],
                ],
              )),
          _card('播放内容',
              child: Column(
                crossAxisAlignment: CrossAxisAlignment.start,
                children: [
                  Wrap(
                    spacing: 8,
                    children: [
                      _chip('打开网址', _ct == 'url', () => setState(() => _ct = 'url')),
                      _chip('导入音视频文件', _ct == 'file', () => setState(() => _ct = 'file')),
                    ],
                  ),
                  if (_ct == 'url') ...[
                    const SizedBox(height: 12),
                    TextField(
                      decoration: const InputDecoration(
                        hintText: 'https:// 或 http://',
                        border: OutlineInputBorder(),
                        isDense: true,
                      ),
                      onChanged: (v) => _url = v,
                    ),
                  ],
                  if (_ct == 'file') ...[
                    const SizedBox(height: 12),
                    OutlinedButton.icon(
                      onPressed: _pickFile,
                      icon: const Icon(Icons.upload_file, size: 18),
                      label: Text(_fileName.isEmpty
                          ? '点击导入文件（mp3 / mp4 等）'
                          : _fileName.split('/').last),
                      style: OutlinedButton.styleFrom(
                        minimumSize: const Size.fromHeight(44),
                      ),
                    ),
                    if (_fileName.isNotEmpty)
                      Padding(
                        padding: const EdgeInsets.only(top: 6),
                        child: Text(
                          _isVideo ? '视频文件' : '音频文件',
                          style: TextStyle(
                              fontSize: 12, color: Colors.grey.shade600),
                        ),
                      ),
                  ],
                ],
              )),
          _card('停止设置',
              child: Column(
                crossAxisAlignment: CrossAxisAlignment.start,
                children: [
                  const Text('自动停止',
                      style: TextStyle(
                          fontSize: 13,
                          fontWeight: FontWeight.w600,
                          color: Colors.grey)),
                  const SizedBox(height: 8),
                  _radioRow(
                    selected: _auto == 'loop',
                    title: '循环 N 次后停止',
                    desc: '按设定次数循环播放，播完自动停止；循环 1 次即播放完自然停止',
                    trailing: _stepper('次', loopVal),
                    onTap: () => setState(() => _auto = 'loop'),
                  ),
                  const SizedBox(height: 8),
                  _radioRow(
                    selected: _auto == 'time',
                    title: '播放 N 分钟后停止',
                    desc: '到设定时长自动停止，不循环',
                    trailing: _stepper('分钟', durVal),
                    onTap: () => setState(() => _auto = 'time'),
                  ),
                  const Divider(height: 24),
                  Row(
                    children: [
                      const Icon(Icons.lock_outline, size: 18, color: Colors.amber),
                      const SizedBox(width: 6),
                      const Expanded(
                        child: Text('锁定 L 分钟',
                            style: TextStyle(
                                fontSize: 14, fontWeight: FontWeight.w600)),
                      ),
                      Switch(
                        value: _lockEnabled,
                        onChanged: (v) => setState(() => _lockEnabled = v),
                      ),
                    ],
                  ),
                  if (_lockEnabled) ...[
                    const SizedBox(height: 2),
                    Text(
                      '前 L 分钟不可停，到点出现关闭按钮；若没手动停，按自动停止条件结束',
                      style: TextStyle(
                          fontSize: 12, color: Colors.grey.shade600),
                    ),
                    const SizedBox(height: 10),
                    _stepper('分钟', lockVal),
                  ],
                ],
              )),
        ],
      ),
      bottomNavigationBar: SafeArea(
        child: Padding(
          padding: const EdgeInsets.all(14),
          child: Row(
            children: [
              Expanded(
                child: OutlinedButton(
                  onPressed: () => Navigator.pop(context),
                  style: OutlinedButton.styleFrom(
                      minimumSize: const Size.fromHeight(46)),
                  child: const Text('取消'),
                ),
              ),
              const SizedBox(width: 12),
              Expanded(
                child: FilledButton(
                  onPressed: _save,
                  style: FilledButton.styleFrom(
                      minimumSize: const Size.fromHeight(46)),
                  child: const Text('保存任务'),
                ),
              ),
            ],
          ),
        ),
      ),
    );
  }

  Widget _card(String title, {required Widget child}) {
    return Card(
      elevation: 0,
      margin: const EdgeInsets.only(bottom: 12),
      shape: RoundedRectangleBorder(
        borderRadius: BorderRadius.circular(14),
        side: BorderSide(color: Colors.grey.shade200),
      ),
      child: Padding(
        padding: const EdgeInsets.all(14),
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            Text(title,
                style: const TextStyle(
                    fontSize: 14, fontWeight: FontWeight.w700)),
            const SizedBox(height: 12),
            child,
          ],
        ),
      ),
    );
  }

  Widget _rowTap(IconData icon, String text, VoidCallback onTap) {
    return InkWell(
      borderRadius: BorderRadius.circular(10),
      onTap: onTap,
      child: Container(
        padding: const EdgeInsets.symmetric(horizontal: 12, vertical: 12),
        decoration: BoxDecoration(
          color: Colors.grey.shade100,
          borderRadius: BorderRadius.circular(10),
        ),
        child: Row(
          children: [
            Icon(icon, size: 18, color: Colors.grey.shade700),
            const SizedBox(width: 8),
            Expanded(
              child: Text(text,
                  style: const TextStyle(
                      fontSize: 14, fontWeight: FontWeight.w500)),
            ),
            Icon(Icons.chevron_right, size: 18, color: Colors.grey.shade400),
          ],
        ),
      ),
    );
  }

  Widget _chip(String label, bool selected, VoidCallback onTap) {
    return ChoiceChip(
      label: Text(label),
      selected: selected,
      onSelected: (_) => onTap(),
    );
  }

  Widget _radioRow({
    required bool selected,
    required String title,
    required String desc,
    required Widget trailing,
    required VoidCallback onTap,
  }) {
    return InkWell(
      borderRadius: BorderRadius.circular(12),
      onTap: onTap,
      child: Container(
        padding: const EdgeInsets.all(12),
        decoration: BoxDecoration(
          borderRadius: BorderRadius.circular(12),
          border: Border.all(
            color: selected ? Colors.indigo : Colors.grey.shade300,
            width: selected ? 1.6 : 1,
          ),
          color: selected ? Colors.indigo.withOpacity(0.05) : null,
        ),
        child: Row(
          children: [
            Icon(
              selected
                  ? Icons.radio_button_checked
                  : Icons.radio_button_off,
              size: 20,
              color: selected ? Colors.indigo : Colors.grey,
            ),
            const SizedBox(width: 10),
            Expanded(
              child: Column(
                crossAxisAlignment: CrossAxisAlignment.start,
                children: [
                  Text(title,
                      style: const TextStyle(
                          fontSize: 13.5, fontWeight: FontWeight.w600)),
                  const SizedBox(height: 2),
                  Text(desc,
                      style:
                          TextStyle(fontSize: 11.5, color: Colors.grey.shade600)),
                ],
              ),
            ),
            trailing,
          ],
        ),
      ),
    );
  }

  Widget _stepper(String unit, ValueNotifier<int> notifier) {
    return Row(
      mainAxisSize: MainAxisSize.min,
      children: [
        _stepBtn(Icons.remove, () => _adjust(notifier, -1)),
        Padding(
          padding: const EdgeInsets.symmetric(horizontal: 8),
          child: ValueListenableBuilder<int>(
            valueListenable: notifier,
            builder: (_, v, __) => Text('$v',
                style: const TextStyle(
                    fontSize: 15,
                    fontWeight: FontWeight.w700,
                    fontFeatures: [FontFeature.tabularFigures()])),
          ),
        ),
        _stepBtn(Icons.add, () => _adjust(notifier, 1)),
        const SizedBox(width: 6),
        Text(unit, style: TextStyle(fontSize: 12, color: Colors.grey.shade600)),
      ],
    );
  }

  Widget _stepBtn(IconData icon, VoidCallback onTap) {
    return InkWell(
      borderRadius: BorderRadius.circular(6),
      onTap: onTap,
      child: Container(
        width: 28,
        height: 28,
        decoration: BoxDecoration(
          color: Colors.grey.shade200,
          borderRadius: BorderRadius.circular(6),
        ),
        child: Icon(icon, size: 16, color: Colors.grey.shade800),
      ),
    );
  }
}
