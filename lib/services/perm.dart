import 'package:flutter/services.dart';

/// 原生权限桥：悬浮窗权限检测与设置页跳转。
///
/// 链接类任务到点需要"后台启动Activity"，依赖悬浮窗权限豁免
/// Android 14+ 的 BAL 拦截；重装 App 后该权限会被重置。
class Perm {
  static const _ch = MethodChannel('scheduled_player_app/perm');

  /// 悬浮窗（"显示在其他应用上层"）权限是否已授予
  static Future<bool> canDrawOverlays() async {
    try {
      return await _ch.invokeMethod<bool>('canDrawOverlays') ?? false;
    } on PlatformException {
      return false;
    }
  }

  /// 跳转到本 App 的悬浮窗授权页（返回 true 表示页面打开成功）
  static Future<bool> requestOverlay() async {
    try {
      return await _ch.invokeMethod<bool>('requestOverlay') ?? false;
    } on PlatformException {
      return false;
    }
  }

  /// 跳转到应用详情页（引导开启 MIUI「后台弹出界面」「锁屏显示」）
  static Future<bool> openAppDetails() async {
    try {
      return await _ch.invokeMethod<bool>('openAppDetails') ?? false;
    } on PlatformException {
      return false;
    }
  }
}
