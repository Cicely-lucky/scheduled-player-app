/// 播放任务数据模型
///
/// 字段设计与 HTML Demo 完全一致：
/// - 频次 freq: daily(每天) / weekly(每周勾选周几) / date(特定日期)
/// - 内容 ct: url(打开网址) / file(导入音视频文件)
/// - 自动停止 auto: loop(循环N次后停止) / time(播放N分钟后停止)
/// - 锁定 lockEnabled: 开启后前 L 分钟隐藏停止按钮，到点出现关闭选项
class PlayTask {
  final int id;
  String name;
  bool enabled;

  String freq; // daily | weekly | date
  Set<int> days; // 1=周一 ... 7=周日（与 Demo 一致）
  String time; // "HH:mm"
  String date; // "yyyy-MM-dd"（freq=date 时有效）

  String ct; // url | file
  String url; // ct=url 时的网址
  String fileName; // ct=file 时导入的文件路径/名称
  bool isVideo; // 导入的是否为视频

  String auto; // loop | time
  int loop; // 循环次数
  int dur; // 播放分钟
  bool lockEnabled; // 是否启用锁定
  int lock; // 锁定分钟

  PlayTask({
    required this.id,
    required this.name,
    this.enabled = true,
    this.freq = 'daily',
    this.days = const {},
    this.time = '08:00',
    this.date = '',
    this.ct = 'url',
    this.url = '',
    this.fileName = '',
    this.isVideo = false,
    this.auto = 'loop',
    this.loop = 1,
    this.dur = 30,
    this.lockEnabled = false,
    this.lock = 10,
  });

  /// 频次的中文描述，如 "每天" / "每周一、三、五" / "2026-09-01"
  String get freqDesc {
    if (freq == 'daily') return '每天';
    if (freq == 'weekly') {
      const names = ['一', '二', '三', '四', '五', '六', '日'];
      final sorted = days.toList()..sort();
      return '每周' + sorted.map((d) => names[d - 1]).join('、');
    }
    return date;
  }

  /// 自动停止描述，如 "循环 3 次后停止" / "播放 30 分钟后停止"
  String get autoDesc {
    if (auto == 'loop') return '循环 $loop 次后停止';
    return '播放 $dur 分钟后停止';
  }

  /// 完整停止描述（含锁定）
  String get stopDesc {
    var s = autoDesc;
    if (lockEnabled) s += '；前 $lock 分钟不可停，到点出现关闭按钮';
    return s;
  }

  /// 内容描述
  String get contentDesc {
    if (ct == 'url') return '打开网址';
    return isVideo ? '播放视频' : '播放音频';
  }

  /// 判断任务在某个时刻是否应被触发
  /// [now] 精确到分钟即可
  bool shouldRunAt(DateTime now) {
    if (!enabled) return false;
    final hm = _hm(now);
    if (hm != time) return false;
    if (freq == 'daily') return true;
    if (freq == 'weekly') return days.contains(now.weekday);
    if (freq == 'date') return date == _ymd(now);
    return false;
  }

  /// 计算任务的下一次触发时间（用于首页"下一个任务"提示）
  DateTime? nextRunAt(DateTime now) {
    if (!enabled) return null;
    DateTime candidate;
    if (freq == 'date') {
      final parts = date.split('-');
      if (parts.length != 3) return null;
      final d = DateTime(int.parse(parts[0]), int.parse(parts[1]),
          int.parse(parts[2]), int.parse(time.split(':')[0]),
          int.parse(time.split(':')[1]));
      return d.isAfter(now) ? d : null;
    }
    if (freq == 'weekly') {
      // 从今天起往后找 8 天，取第一个匹配周几且时间大于 now 的
      for (var i = 0; i <= 7; i++) {
        final day = now.add(Duration(days: i));
        if (!days.contains(day.weekday)) continue;
        candidate = DateTime(day.year, day.month, day.day,
            int.parse(time.split(':')[0]), int.parse(time.split(':')[1]));
        if (candidate.isAfter(now)) return candidate;
      }
      return null;
    }
    // daily：今天的时间若已过则明天
    candidate = DateTime(now.year, now.month, now.day,
        int.parse(time.split(':')[0]), int.parse(time.split(':')[1]));
    if (!candidate.isAfter(now)) candidate = candidate.add(const Duration(days: 1));
    return candidate;
  }

  Map<String, dynamic> toJson() => {
        'id': id,
        'name': name,
        'enabled': enabled,
        'freq': freq,
        'days': days.toList(),
        'time': time,
        'date': date,
        'ct': ct,
        'url': url,
        'fileName': fileName,
        'isVideo': isVideo,
        'auto': auto,
        'loop': loop,
        'dur': dur,
        'lockEnabled': lockEnabled,
        'lock': lock,
      };

  factory PlayTask.fromJson(Map<String, dynamic> j) => PlayTask(
        id: j['id'] ?? DateTime.now().millisecondsSinceEpoch,
        name: j['name'] ?? '未命名任务',
        enabled: j['enabled'] ?? true,
        freq: j['freq'] ?? 'daily',
        days: ((j['days'] as List?) ?? []).map((e) => e as int).toSet(),
        time: j['time'] ?? '08:00',
        date: j['date'] ?? '',
        ct: j['ct'] ?? 'url',
        url: j['url'] ?? '',
        fileName: j['fileName'] ?? '',
        isVideo: j['isVideo'] ?? false,
        auto: j['auto'] ?? 'loop',
        loop: j['loop'] ?? 1,
        dur: j['dur'] ?? 30,
        lockEnabled: j['lockEnabled'] ?? false,
        lock: j['lock'] ?? 10,
      );

  static String _hm(DateTime d) =>
      '${d.hour.toString().padLeft(2, '0')}:${d.minute.toString().padLeft(2, '0')}';

  static String _ymd(DateTime d) =>
      '${d.year.toString().padLeft(4, '0')}-${d.month.toString().padLeft(2, '0')}-${d.day.toString().padLeft(2, '0')}';
}
