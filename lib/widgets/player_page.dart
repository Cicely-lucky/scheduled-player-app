import 'dart:async';
import 'dart:io';

import 'package:flutter/material.dart';
import 'package:just_audio/just_audio.dart';
import 'package:video_player/video_player.dart';

import '../models/task.dart';

/// 播放页：全屏播放
///
/// 行为与 HTML Demo 一致：
/// - 锁定 L 分钟：到点前隐藏停止按钮，显示琥珀色倒计时；到点解锁
/// - auto=time：播放满 N 分钟自动停止
/// - auto=loop：循环播放，播完 N 次停止
class PlayerPage extends StatefulWidget {
  final PlayTask task;
  const PlayerPage({super.key, required this.task});

  @override
  State<PlayerPage> createState() => _PlayerPageState();
}

class _PlayerPageState extends State<PlayerPage> {
  PlayTask get t => widget.task;

  AudioPlayer? _audio;
  VideoPlayerController? _video;
  Timer? _timer;
  int _sec = 0;
  int _completed = 0;
  bool _playing = true;
  bool _unlocked = false;
  bool _initFailed = false;

  @override
  void initState() {
    super.initState();
    _initPlayer();
    _timer = Timer.periodic(const Duration(seconds: 1), _tick);
  }

  @override
  void dispose() {
    _timer?.cancel();
    _audio?.dispose();
    _video?.dispose();
    super.dispose();
  }

  Future<void> _initPlayer() async {
    try {
      if (t.isVideo) {
        final c = t.ct == 'url'
            ? VideoPlayerController.networkUrl(Uri.parse(t.url))
            : VideoPlayerController.file(File(t.fileName));
        _video = c;
        await c.initialize();
        if (!mounted) return;
        setState(() {});
        c.play();
        c.addListener(() {
          final v = c.value;
          if (v.isInitialized &&
              !v.isPlaying &&
              v.position >= v.duration &&
              t.auto == 'loop') {
            _completed++;
            if (_completed >= t.loop) {
              _stop(autoStop: false);
            } else {
              c.seekTo(Duration.zero);
              c.play();
            }
          }
        });
      } else {
        final a = AudioPlayer();
        _audio = a;
        if (t.ct == 'url') {
          await a.setUrl(t.url);
        } else {
          await a.setFilePath(t.fileName);
        }
        if (!mounted) return;
        setState(() {});
        a.play();
        a.processingStateStream.listen((state) {
          if (state == ProcessingState.completed) {
            _completed++;
            if (t.auto == 'loop' && _completed >= t.loop) {
              _stop(autoStop: false);
            } else {
              a.seek(Duration.zero);
              a.play();
            }
          }
        });
      }
    } catch (_) {
      if (mounted) {
        setState(() => _initFailed = true);
        _showSnack('无法播放该内容，请检查文件或网址');
      }
    }
  }

  void _tick(Timer timer) {
    if (!mounted) return;
    if (!_playing) return;
    _sec++;
    setState(() {});
    // 锁定到点解锁
    if (t.lockEnabled && !_unlocked && _sec >= t.lock * 60) {
      _unlocked = true;
      _showSnack('已播放满 ${t.lock} 分钟，出现关闭选项，可手动停止');
    }
    // 播放 N 分钟后自动停止
    if (t.auto == 'time' && _sec >= t.dur * 60) {
      _stop(autoStop: true);
    }
  }

  void _stop({required bool autoStop}) {
    _timer?.cancel();
    _audio?.stop();
    _video?.pause();
    if (autoStop && mounted) {
      _showSnack('已播放满 ${t.dur} 分钟，自动停止');
    }
    if (mounted) Navigator.pop(context);
  }

  void _togglePause() {
    setState(() => _playing = !_playing);
    if (t.isVideo) {
      _playing ? _video?.play() : _video?.pause();
    } else {
      _playing ? _audio?.play() : _audio?.pause();
    }
  }

  void _showSnack(String msg) {
    ScaffoldMessenger.of(context)
        .showSnackBar(SnackBar(content: Text(msg), behavior: SnackBarBehavior.floating));
  }

  String get _fmtTime {
    final h = _sec ~/ 3600;
    final m = (_sec % 3600) ~/ 60;
    final s = _sec % 60;
    String two(int n) => n.toString().padLeft(2, '0');
    return h > 0 ? '${two(h)}:${two(m)}:${two(s)}' : '${two(m)}:${two(s)}';
  }

  String get _lockRemain {
    final rem = t.lock * 60 - _sec;
    if (rem <= 0) return '00:00';
    final m = rem ~/ 60;
    final s = rem % 60;
    String two(int n) => n.toString().padLeft(2, '0');
    return '${two(m)}:${two(s)}';
  }

  @override
  Widget build(BuildContext context) {
    final locked = t.lockEnabled && !_unlocked;

    return Scaffold(
      backgroundColor: Colors.black,
      body: SafeArea(
        child: Column(
          children: [
            // 顶部信息
            Padding(
              padding: const EdgeInsets.fromLTRB(16, 12, 16, 0),
              child: Row(
                children: [
                  IconButton(
                    icon: const Icon(Icons.keyboard_arrow_down,
                        color: Colors.white70, size: 28),
                    onPressed: () => Navigator.pop(context),
                    tooltip: '收起',
                  ),
                  const SizedBox(width: 4),
                  Expanded(
                    child: Text(
                      t.name,
                      style: const TextStyle(
                          color: Colors.white, fontSize: 16, fontWeight: FontWeight.w600),
                      overflow: TextOverflow.ellipsis,
                    ),
                  ),
                  if (t.isVideo)
                    const Icon(Icons.videocam, color: Colors.white54, size: 18)
                  else
                    const Icon(Icons.audiotrack, color: Colors.white54, size: 18),
                ],
              ),
            ),
            const Spacer(),
            // 视频区域
            if (t.isVideo && _video != null && _video!.value.isInitialized)
              Padding(
                padding: const EdgeInsets.symmetric(horizontal: 16),
                child: ClipRRect(
                  borderRadius: BorderRadius.circular(12),
                  child: AspectRatio(
                    aspectRatio: _video!.value.aspectRatio,
                    child: VideoPlayer(_video!),
                  ),
                ),
              ),
            if (t.isVideo)
              const SizedBox(height: 24)
            else
              const SizedBox(height: 8),
            // 大计时
            Text(
              _fmtTime,
              style: const TextStyle(
                color: Colors.white,
                fontSize: 56,
                fontWeight: FontWeight.w700,
                fontFeatures: [FontFeature.tabularFigures()],
              ),
            ),
            const SizedBox(height: 10),
            Text(
              t.ct == 'url' ? '打开网址' : (t.isVideo ? '播放视频' : '播放音频'),
              style: const TextStyle(color: Colors.white38, fontSize: 13),
            ),
            const Spacer(),
            // 锁定徽标 / 控制按钮
            if (locked)
              Container(
                margin: const EdgeInsets.only(bottom: 40),
                padding:
                    const EdgeInsets.symmetric(horizontal: 16, vertical: 8),
                decoration: BoxDecoration(
                  color: Colors.amber.withOpacity(0.18),
                  borderRadius: BorderRadius.circular(999),
                ),
                child: Row(
                  mainAxisSize: MainAxisSize.min,
                  children: [
                    const Icon(Icons.lock, color: Colors.amber, size: 14),
                    const SizedBox(width: 6),
                    Text(
                      '剩余 $_lockRemain 后可停止',
                      style: const TextStyle(
                          color: Colors.amber, fontSize: 12.5),
                    ),
                  ],
                ),
              )
            else if (!_initFailed)
              Padding(
                padding: const EdgeInsets.only(bottom: 32),
                child: Row(
                  mainAxisAlignment: MainAxisAlignment.center,
                  children: [
                    IconButton(
                      iconSize: 30,
                      color: Colors.white70,
                      icon: Icon(_playing
                          ? Icons.pause_circle_outline
                          : Icons.play_circle_outline),
                      onPressed: _togglePause,
                    ),
                    const SizedBox(width: 40),
                    IconButton(
                      iconSize: 34,
                      color: const Color(0xFFFCA5A5),
                      icon: const Icon(Icons.stop_circle_outlined),
                      onPressed: () => _stop(autoStop: false),
                    ),
                  ],
                ),
              ),
            const SizedBox(height: 8),
          ],
        ),
      ),
    );
  }
}
