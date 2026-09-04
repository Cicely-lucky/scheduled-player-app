package com.cici.scheduledplayer

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import io.flutter.embedding.android.FlutterActivity
import io.flutter.embedding.engine.FlutterEngine
import io.flutter.plugin.common.MethodChannel

/**
 * 主入口 + 权限自检桥。
 *
 * 背景：B站链接任务依赖"悬浮窗权限"（SYSTEM_ALERT_WINDOW）豁免
 * Android 14+ 的后台启动Activity（BAL）拦截；小米机型还需
 * MIUI「后台弹出界面」(appops 10021)、「锁屏显示」(10020)。
 * 重装/更新 App 后这些授权会被系统重置，此前只能连电脑 adb 重授。
 *
 * 本类提供 MethodChannel（scheduled_player_app/perm）：
 * - canDrawOverlays：检测悬浮窗权限（AOSP 标准 API，MIUI 同样生效）
 * - requestOverlay：跳转系统"在其他应用上层显示"设置页
 * - openAppDetails：跳转应用详情页（引导用户开 MIUI 专属权限）
 */
class MainActivity : FlutterActivity() {

    private val channelName = "scheduled_player_app/perm"

    override fun configureFlutterEngine(flutterEngine: FlutterEngine) {
        super.configureFlutterEngine(flutterEngine)
        MethodChannel(flutterEngine.dartExecutor.binaryMessenger, channelName)
            .setMethodCallHandler { call, result ->
                when (call.method) {
                    "canDrawOverlays" -> {
                        val ok = if (Build.VERSION.SDK_INT >= 23) {
                            Settings.canDrawOverlays(applicationContext)
                        } else {
                            true
                        }
                        result.success(ok)
                    }
                    "requestOverlay" -> {
                        // 直达本App的悬浮窗授权页；个别ROM无此页面时
                        // 回退到应用详情页
                        result.success(startSettingsPage(
                            Intent(
                                Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                                Uri.parse("package:$packageName")
                            )
                        ))
                    }
                    "openAppDetails" -> {
                        result.success(startSettingsPage(
                            Intent(
                                Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                                Uri.parse("package:$packageName")
                            )
                        ))
                    }
                    else -> result.notImplemented()
                }
            }
    }

    private fun startSettingsPage(intent: Intent): Boolean {
        return try {
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            startActivity(intent)
            true
        } catch (e: Exception) {
            false
        }
    }
}
