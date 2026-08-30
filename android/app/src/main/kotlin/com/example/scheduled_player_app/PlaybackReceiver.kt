package com.example.scheduled_player_app

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Log

/**
 * 后台自动播放的桥接接收器：
 * Dart 闹钟回调（后台 isolate）通过 android_intent_plus 发显式广播到此处，
 * 再由这里启动 PlaybackService 前台服务。
 *
 * 为什么多这一跳：Dart 侧插件没有直接 startForegroundService 的能力，
 * 而显式广播（同 App、exported=false）不受任何系统限制；
 * 精确闹钟触发后 App 处于临时白名单窗口，此处允许启动前台服务。
 *
 * 另处理 OPEN_URL：到点直接拉起 B 站/浏览器（临时白名单窗口内允许
 * 后台启动 Activity，与闹钟 App 弹响铃界面同机制）。MIUI 需用户开启
 * "后台弹出界面"权限，否则会被 ROM 拦截（日志可见）。
 */
class PlaybackReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        Log.d("SP-Alarm", "receiver got broadcast, action=${intent.action}, path=${intent.getStringExtra("path")}")

        // 网址任务：到点直接打开（B 站 App / 浏览器）
        if (intent.action == "com.example.scheduled_player_app.OPEN_URL") {
            val url = intent.getStringExtra("url")
            if (url.isNullOrEmpty()) {
                Log.e("SP-Alarm", "OPEN_URL but url is empty")
                return
            }
            try {
                val view = Intent(Intent.ACTION_VIEW, Uri.parse(url))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                context.startActivity(view)
                Log.d("SP-Alarm", "startActivity ok: $url")
            } catch (e: Exception) {
                // MIUI 未开"后台弹出界面"权限等场景：日志定位，Dart 侧无法感知
                Log.e("SP-Alarm", "startActivity failed: $e")
            }
            return
        }

        val service = Intent(context, PlaybackService::class.java)
        // 转发全部 extras（path/title/loop/durMin/lockMin）
        service.putExtras(intent)
        try {
            context.startForegroundService(service)
            Log.d("SP-Alarm", "startForegroundService ok")
        } catch (e: Exception) {
            // 极端 ROM 限制下的兜底（可能被系统拒绝，Dart 侧还有通知方案兜底）
            Log.e("SP-Alarm", "startForegroundService failed: $e")
            try {
                context.startService(service)
                Log.d("SP-Alarm", "startService (fallback) ok")
            } catch (e2: Exception) {
                Log.e("SP-Alarm", "startService fallback failed: $e2")
            }
        }
    }
}
