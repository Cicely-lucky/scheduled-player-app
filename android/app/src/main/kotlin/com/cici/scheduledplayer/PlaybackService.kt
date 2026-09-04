package com.cici.scheduledplayer

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.Log

/**
 * 后台自动播放前台服务：
 * 由闹钟回调（Dart 后台 isolate）经 PlaybackReceiver 拉起，
 * 直接用 MediaPlayer 播放音频文件——无需任何界面、无需点击通知，
 * 亮屏使用中 / 锁屏 / 熄屏均可自动开播（与闹钟 App 响铃同机制）。
 *
 * 播放期间通知栏常驻"正在播放"通知（含停止按钮），随播放停止自动消失。
 * Android 12+ 从后台启动前台服务本被禁止，但精确闹钟（setAlarmClock）
 * 触发后 App 处于临时白名单窗口内，允许启动——这是本方案的合法性依据。
 */
class PlaybackService : Service() {

    companion object {
        const val ACTION_STOP = "com.cici.scheduledplayer.PLAYBACK_STOP"
        const val EXTRA_PATH = "path"
        const val EXTRA_TITLE = "title"
        const val EXTRA_LOOP = "loop"
        const val EXTRA_DUR_MIN = "durMin"
        const val EXTRA_LOCK_MIN = "lockMin"
        const val CHANNEL_ID = "playback_channel"
        const val NOTIF_ID = 20001
    }

    private var player: MediaPlayer? = null
    private val handler = Handler(Looper.getMainLooper())
    private var loopLeft = 1
    private var title = "定时播放"

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // 通知栏"停止"按钮：通过带 ACTION_STOP 的 startService 意图进入
        if (intent?.action == ACTION_STOP) {
            stopPlayback()
            return START_NOT_STICKY
        }

        val path = intent?.getStringExtra(EXTRA_PATH)
        if (path.isNullOrEmpty()) {
            Log.e("SP-Alarm", "service started but path is empty/null")
            stopSelf()
            return START_NOT_STICKY
        }
        Log.d("SP-Alarm", "service onStartCommand, path=$path")

        title = intent.getStringExtra(EXTRA_TITLE) ?: "定时播放"
        val durMin = intent.getIntExtra(EXTRA_DUR_MIN, 0)
        val lockMin = intent.getIntExtra(EXTRA_LOCK_MIN, 0)
        // 时长模式：循环播放直到 N 分钟到点；循环模式：播 N 遍后自然停止
        loopLeft = if (durMin > 0) Int.MAX_VALUE
        else intent.getIntExtra(EXTRA_LOOP, 1).coerceAtLeast(1)

        // 必须在 startForegroundService 后 5 秒内调用 startForeground，
        // 否则系统会 ANR 崩溃——先立即上通知，再开始播放
        startForegroundCompat(buildNotification(locked = lockMin > 0, lockRemainMin = lockMin))
        startPlay(path, durMin)

        // 锁定期结束：更新通知，放出"停止"按钮
        if (lockMin > 0) {
            handler.postDelayed({
                val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
                nm.notify(NOTIF_ID, buildNotification(locked = false, lockRemainMin = 0))
            }, lockMin * 60_000L)
        }
        return START_NOT_STICKY
    }

    private fun startPlay(path: String, durMin: Int) {
        try {
            player?.release()
            player = MediaPlayer().apply {
                setDataSource(path)
                setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                        .build()
                )
                setOnCompletionListener { p ->
                    loopLeft -= 1
                    if (loopLeft <= 0) {
                        stopPlayback()
                    } else {
                        // 播完后 start() 会从头重播
                        p.start()
                    }
                }
                setOnErrorListener { _, _, _ ->
                    stopPlayback()
                    true
                }
                prepare()
                start()
                Log.d("SP-Alarm", "MediaPlayer playing: $path")
            }
            if (durMin > 0) {
                handler.postDelayed({ stopPlayback() }, durMin * 60_000L)
            }
        } catch (e: Exception) {
            // 文件丢失/损坏等：记录原因后收摊（Dart 侧有通知方案兜底）
            Log.e("SP-Alarm", "MediaPlayer failed: $e")
            stopPlayback()
        }
    }

    private fun stopPlayback() {
        handler.removeCallbacksAndMessages(null)
        try {
            player?.release()
        } catch (_: Exception) {
        }
        player = null
        if (Build.VERSION.SDK_INT >= 24) {
            stopForeground(STOP_FOREGROUND_REMOVE)
        } else {
            @Suppress("DEPRECATION")
            stopForeground(true)
        }
        stopSelf()
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

    private fun buildNotification(locked: Boolean, lockRemainMin: Int): Notification {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= 26) {
            nm.createNotificationChannel(
                NotificationChannel(
                    CHANNEL_ID,
                    "后台播放",
                    NotificationManager.IMPORTANCE_LOW
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
        builder.setSmallIcon(android.R.drawable.ic_media_play)
            .setContentTitle("正在播放：$title")
            .setContentText(
                if (locked) "锁定中，$lockRemainMin 分钟后可停止"
                else "定时播放中，点击通知栏停止按钮可结束"
            )
            .setOngoing(true)
            .setContentIntent(contentIntent)

        // 锁定期内不放停止按钮（与 App 内"前 L 分钟隐藏停止"规则一致）
        if (!locked && Build.VERSION.SDK_INT >= 23) {
            val stopPi = PendingIntent.getService(
                this, 1,
                Intent(this, PlaybackService::class.java).setAction(ACTION_STOP),
                piFlags
            )
            builder.addAction(
                Notification.Action.Builder(
                    android.R.drawable.ic_media_pause,
                    "停止",
                    stopPi
                ).build()
            )
        }
        return builder.build()
    }
}
