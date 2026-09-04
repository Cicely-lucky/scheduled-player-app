package com.cici.scheduledplayer

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
 * 另处理三种 action：
 * - OPEN_URL：到点打开网址。统一经 UrlBridgeService 前台服务中转——
 *   startForeground 后进程处于 FOREGROUND_SERVICE 状态（后台启动
 *   Activity 的文档豁免场景），服务内 startActivity 不再被 BAL 拦截。
 *   60 秒内同 url 去重防双开。
 * - SYNC_URL_ALARMS：全量同步网址任务的原生闹钟（JSON 列表）。
 * - PLAYBACK_START：拉起 PlaybackService 前台服务播放音频。
 *
 * 工具方法（extractUrlCompat / shouldOpen / showUrlFallbackNotification）
 * 设为 companion 供 UrlBridgeService 复用。
 */
class PlaybackReceiver : BroadcastReceiver() {

    companion object {
        /** 进程级去重：url → 上次发起 startActivity 的 elapsedRealtime */
        private val lastOpenAt = HashMap<String, Long>()
        private const val OPEN_DEDUP_MS = 60_000L

        /** 从"标题+链接"混合文本中提取第一个 http(s) 网址；没有则原样返回 */
        fun extractUrlCompat(raw: String): String {
            val m = Regex("https?://[A-Za-z0-9./?%&=_#~:\\-+]+").find(raw)
            return m?.value ?: raw.trim()
        }

        /** 60 秒去重：返回 true 表示可以打开（同 url 近期未打开过） */
        @Synchronized
        fun shouldOpen(url: String): Boolean {
            val now = android.os.SystemClock.elapsedRealtime()
            val last = lastOpenAt[url]
            if (last != null && now - last < OPEN_DEDUP_MS) return false
            lastOpenAt[url] = now
            return true
        }

        /**
         * 后台拉起失败的兜底通知：点通知打开网址。
         * 渠道与 Dart 侧 alarm_channel 保持一致（不存在则创建），
         * 保证被 MIUI 拦截（未开"后台弹出界面"）时用户仍有入口。
         */
        fun showUrlFallbackNotification(context: Context, url: String) {
            try {
                val nm = context.getSystemService(Context.NOTIFICATION_SERVICE)
                    as android.app.NotificationManager
                val channelId = "alarm_channel"
                if (nm.getNotificationChannel(channelId) == null) {
                    nm.createNotificationChannel(
                        android.app.NotificationChannel(
                            channelId, "定时提醒",
                            android.app.NotificationManager.IMPORTANCE_HIGH
                        )
                    )
                }
                val view = Intent(Intent.ACTION_VIEW, Uri.parse(url))
                val pi = android.app.PendingIntent.getActivity(
                    context, url.hashCode(), view,
                    android.app.PendingIntent.FLAG_UPDATE_CURRENT or
                        android.app.PendingIntent.FLAG_IMMUTABLE
                )
                val n = android.app.Notification.Builder(context, channelId)
                    .setSmallIcon(context.applicationInfo.icon)
                    .setContentTitle("定时任务到点")
                    .setContentText("点击打开链接")
                    .setContentIntent(pi)
                    .setAutoCancel(true)
                    .build()
                nm.notify(url.hashCode(), n)
                Log.d("SP-Alarm", "fallback notification shown")
            } catch (e: Exception) {
                Log.e("SP-Alarm", "fallback notification failed: $e")
            }
        }
    }

    override fun onReceive(context: Context, intent: Intent) {
        Log.d("SP-Alarm", "receiver got broadcast, action=${intent.action}, path=${intent.getStringExtra("path")}")

        // 全量同步网址任务的原生闹钟（Dart 主/后台 isolate 均可发）
        if (intent.action == "com.cici.scheduledplayer.SYNC_URL_ALARMS") {
            val json = intent.getStringExtra("alarms") ?: "[]"
            val items = try {
                val arr = org.json.JSONArray(json)
                (0 until arr.length()).map { i ->
                    val o = arr.getJSONObject(i)
                    UrlAlarmScheduler.Item(
                        o.getInt("id"), o.getLong("at"), o.getString("url")
                    )
                }
            } catch (e: Exception) {
                Log.e("SP-Alarm", "SYNC_URL_ALARMS parse error: $e")
                emptyList()
            }
            UrlAlarmScheduler.sync(context, items)
            return
        }

        // 网址任务：到点打开（B 站 App / 浏览器）
        // 统一经 UrlBridgeService 前台服务中转：广播接收者里直接 startActivity
        // 会被 AOSP 后台启动拦截（procState=RECEIVER，BAL_BLOCK code=102，
        // 小米 Android 16 实测）；startForegroundService 拉起前台服务后进程
        // 处于 FOREGROUND_SERVICE 状态，服务内 startActivity 属豁免场景。
        if (intent.action == "com.cici.scheduledplayer.OPEN_URL") {
            val raw = intent.getStringExtra("url")
            if (raw.isNullOrEmpty()) {
                Log.e("SP-Alarm", "OPEN_URL but url is empty")
                return
            }
            // 用户常从 B 站 App 分享复制"【标题】 https://b23.tv/xxx"整段文字，
            // 直接 Uri.parse 整段会 ActivityNotFoundException；先提取纯网址
            val url = extractUrlCompat(raw)
            val fromNativeAlarm = intent.getBooleanExtra("from_native_alarm", false)
            Log.d("SP-Alarm", "OPEN_URL raw=$raw")
            Log.d("SP-Alarm", "OPEN_URL extracted=$url, fromNativeAlarm=$fromNativeAlarm")

            // 原生闹钟（getBroadcast）与 Dart 定时器都可能触发，60 秒内同 url 只开一次
            if (!shouldOpen(url)) {
                Log.d("SP-Alarm", "OPEN_URL skip (opened recently)")
                return
            }

            try {
                val svc = Intent(context, UrlBridgeService::class.java)
                    .putExtra(UrlBridgeService.EXTRA_URL, url)
                context.startForegroundService(svc)
                Log.d("SP-Alarm", "startForegroundService(UrlBridgeService) ok")
            } catch (e: Exception) {
                Log.e("SP-Alarm", "startForegroundService(UrlBridgeService) failed: $e")
                showUrlFallbackNotification(context, url)
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
