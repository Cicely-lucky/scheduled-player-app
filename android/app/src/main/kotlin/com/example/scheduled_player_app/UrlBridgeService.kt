package com.example.scheduled_player_app

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.Log

/**
 * 网址任务自动打开的前台服务桥（BAL 豁免的核心一跳）。
 *
 * 链路：闹钟/定时器到点 → PlaybackReceiver（广播）→ startForegroundService 拉起本服务
 * → startForeground 后进程处于 FOREGROUND_SERVICE 状态（Android 后台启动 Activity
 * 限制的文档豁免场景之一）→ 在服务内 startActivity 打开目标网址 → 1.5s 后收摊。
 *
 * 为什么必须这一跳（小米 Android 16 实测铁证，15:50 / 16:00 两次）：
 * 1) 闹钟 PendingIntent 用 getActivity + opts 显式 opt-in BAL
 *    （balAllowedByPiCreator=ALLOW_BAL）→ 仍被拦 (BAL_BLOCK result code=102)，
 *    因为发送者 system uid 未 opt-in（balAllowedByPiSender=NONE）；
 * 2) MIUI「后台弹出界面」权限（MIUIOP 10021）已开启 → AOSP BAL 仍拦；
 * 3) 广播接收者里直接 startActivity（procState=RECEIVER）→ 仍拦。
 * 唯一剩余的标准豁免：进程处于 FOREGROUND_SERVICE 状态时后台启动 Activity 被放行。
 *
 * 前台服务启动合法性：精确闹钟（setAlarmClock）触发后 App 处于系统临时白名单
 * （temporaryAppAllowlistDuration=10000），Android 12+ 允许从广播接收者拉起前台服务。
 */
class UrlBridgeService : Service() {

    companion object {
        const val EXTRA_URL = "url"
        const val CHANNEL_ID = "url_bridge_channel"
        const val NOTIF_ID = 20002
    }

    private val handler = Handler(Looper.getMainLooper())

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val raw = intent?.getStringExtra(EXTRA_URL) ?: ""
        val url = PlaybackReceiver.extractUrlCompat(raw)
        if (url.isEmpty()) {
            Log.e("SP-Alarm", "UrlBridgeService: url empty")
            stopSelf()
            return START_NOT_STICKY
        }
        Log.d("SP-Alarm", "UrlBridgeService onStartCommand, url=$url")

        // 立即转前台：进程状态变 FOREGROUND_SERVICE，BAL 放行
        startForegroundCompat(buildNotification())

        // 前台服务内 startActivity 属后台启动豁免场景
        val opened = openUrl(url)

        // 短暂保持前台保证启动过渡完成，然后收摊（通知随之消失）
        handler.postDelayed({
            stopForegroundCompat()
            stopSelf()
        }, if (opened) 1_500L else 300L)
        return START_NOT_STICKY
    }

    private fun openUrl(url: String): Boolean {
        try {
            val view = Intent(Intent.ACTION_VIEW, Uri.parse(url))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            if (view.resolveActivity(packageManager) == null) {
                Log.e("SP-Alarm", "UrlBridgeService: no activity for $url")
                PlaybackReceiver.showUrlFallbackNotification(this, url)
                return false
            }
            startActivity(view)
            Log.d("SP-Alarm", "UrlBridgeService startActivity ok: $url")
            return true
        } catch (e: Exception) {
            Log.e("SP-Alarm", "UrlBridgeService startActivity failed: $e")
            PlaybackReceiver.showUrlFallbackNotification(this, url)
            return false
        }
    }

    private fun startForegroundCompat(notification: Notification) {
        if (Build.VERSION.SDK_INT >= 29) {
            startForeground(
                NOTIF_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK
            )
        } else {
            startForeground(NOTIF_ID, notification)
        }
    }

    private fun stopForegroundCompat() {
        if (Build.VERSION.SDK_INT >= 24) {
            stopForeground(STOP_FOREGROUND_REMOVE)
        } else {
            @Suppress("DEPRECATION")
            stopForeground(true)
        }
    }

    private fun buildNotification(): Notification {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= 26) {
            nm.createNotificationChannel(
                NotificationChannel(
                    CHANNEL_ID,
                    "定时任务",
                    NotificationManager.IMPORTANCE_MIN
                )
            )
        }
        val piFlags = PendingIntent.FLAG_UPDATE_CURRENT or
                (if (Build.VERSION.SDK_INT >= 23) PendingIntent.FLAG_IMMUTABLE else 0)
        val contentIntent = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java),
            piFlags
        )
        val builder = if (Build.VERSION.SDK_INT >= 26) {
            Notification.Builder(this, CHANNEL_ID)
        } else {
            @Suppress("DEPRECATION")
            Notification.Builder(this)
        }
        return builder
            .setSmallIcon(android.R.drawable.ic_menu_share)
            .setContentTitle("定时任务")
            .setContentText("正在打开链接…")
            .setOngoing(true)
            .setContentIntent(contentIntent)
            .build()
    }
}
