package com.example.scheduled_player_app

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * 后台自动播放的桥接接收器：
 * Dart 闹钟回调（后台 isolate）通过 android_intent_plus 发显式广播到此处，
 * 再由这里启动 PlaybackService 前台服务。
 *
 * 为什么多这一跳：Dart 侧插件没有直接 startForegroundService 的能力，
 * 而显式广播（同 App、exported=false）不受任何系统限制；
 * 精确闹钟触发后 App 处于临时白名单窗口，此处允许启动前台服务。
 */
class PlaybackReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val service = Intent(context, PlaybackService::class.java)
        // 转发全部 extras（path/title/loop/durMin/lockMin）
        service.putExtras(intent)
        try {
            context.startForegroundService(service)
        } catch (_: Exception) {
            // 极端 ROM 限制下的兜底（可能被系统拒绝，Dart 侧还有通知方案兜底）
            try {
                context.startService(service)
            } catch (_: Exception) {
            }
        }
    }
}
