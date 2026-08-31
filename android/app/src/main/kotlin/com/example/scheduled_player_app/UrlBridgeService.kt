package com.example.scheduled_player_app

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.Log

/**
 * 网址任务自动打开的前台服务桥（BAL 豁免的跳板）。
 *
 * 链路：闹钟/定时器到点 → PlaybackReceiver（广播）→ startForegroundService 拉起本服务
 * → startForeground 转正 → 拉起 UrlBridgeActivity（showWhenLocked + turnScreenOn，
 * 锁屏之上可见）→ Activity 内打开目标链接 → Activity 延迟 finish、服务收摊。
 *
 * 为什么需要本服务（小米 Android 16 实测铁证，15:50 / 16:00 / 16:25 三次）：
 * 1) 闹钟 PendingIntent 用 getActivity + opts 显式 opt-in BAL → 仍被拦
 *    （balAllowedByPiSender=NONE，发送者 system uid 未 opt-in）；
 * 2) MIUI「后台弹出界面」权限（MIUIOP 10021）已开启 → AOSP BAL 仍拦；
 * 3) FOREGROUND_SERVICE 进程状态本身也不豁免（callingUidHasVisibleActivity=false）。
 * 真正可靠的豁免是 SYSTEM_ALERT_WINDOW（悬浮窗）权限——17:25 实测
 * BAL 判定变为 BAL_ALLOW_SAW_PERMISSION，放行。
 *
 * 17:25 实测补充：服务内直接 startActivity 目标 App 虽过 BAL，但锁屏时被
 * MIUI KeyguardLocked 拦截——所以目标必须由带 showWhenLocked 的自家 Activity
 * （UrlBridgeActivity）发起，本服务只做拉起跳板。
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

        // 立即转前台：进程状态变 FOREGROUND_SERVICE
        startForegroundCompat(buildNotification())

        // 拉起锁屏可见的中转 Activity：BAL 由悬浮窗豁免放行，
        // Activity 带 showWhenLocked/turnScreenOn 绕过 MIUI 锁屏拦截
        try {
            val act = Intent(this, UrlBridgeActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                .putExtra(UrlBridgeActivity.EXTRA_URL, url)
            startActivity(act)
            Log.d("SP-Alarm", "UrlBridgeService -> UrlBridgeActivity launched")
        } catch (e: Exception) {
            Log.e("SP-Alarm", "UrlBridgeService launch activity failed: $e")
            PlaybackReceiver.showUrlFallbackNotification(this, url)
        }

        // 短暂保持前台保证启动过渡完成，然后收摊（通知随之消失）
        handler.postDelayed({
            stopForegroundCompat()
            stopSelf()
        }, 3_000L)
        return START_NOT_STICKY
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
