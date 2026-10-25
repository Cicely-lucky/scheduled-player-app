package com.cici.scheduledplayer

import android.app.KeyguardManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.util.Log

/**
 * 网址任务自动打开的前台服务桥（BAL 豁免的跳板 + 亮屏保活）。
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
 * 2026-10-22 新增【亮屏保活】：晨间实测（无安全锁、锁屏过夜场景）整条链路
 * 全通、B站成功冷启动到目标视频页，但之后无人触屏，系统到达熄屏超时
 * （短至十几秒）后 B站随熄屏立即暂停，用户醒来只见"停在视频页没播起来"。
 * 修复：非安全锁且触发时处于锁屏的场景，打开目标后由本服务持有
 * SCREEN_BRIGHT_WAKE_LOCK 保持亮屏（默认 30 分钟），为 B站自动起播并持续
 * 播放兜底（B站播放中自带 FLAG_KEEP_SCREEN_ON，此锁主要保起播前空窗和
 * 意外熄屏）。三种提前收摊信号：用户按电源键熄屏 / 点通知"提前结束" /
 * 超时自动释放（acquire(timeout) 双保险，即使收摊逻辑被 ROM 干扰也不超时）。
 * 安全锁（PIN/指纹）下 B站只能压在锁屏之下不会自动播，亮屏徒耗电，维持
 * 原 3 秒过渡收摊行为；触发时已解锁亮屏（用户在场测试）同样不需要保活。
 *
 * 2026-10-25 根因修正：实测 isKeyguardSecure() 在 HyperOS 上误报 true
 * （滑动锁 + B站前台正常播放的条件下），导致保活分支从未进入、亮屏
 * 12 秒即灭、播放 19 秒即断。判定改为「触发时屏幕熄灭」（PowerManager
 * .isInteractive），不再信任 keyguard 标志；另加每分钟唤醒锁自查续命，
 * 防 ROM 剥离。安全锁下持屏意义降低，但宁可多保 30 分钟也不重蹈覆辙。
 */
class UrlBridgeService : Service() {

    companion object {
        const val EXTRA_URL = "url"
        const val CHANNEL_ID = "url_bridge_channel"
        const val NOTIF_ID = 20002

        /** 常驻通知的"提前结束"动作：点通知即释放亮屏锁并收摊 */
        const val ACTION_STOP = "com.cici.scheduledplayer.URL_BRIDGE_STOP"

        /** 非安全锁场景：打开目标后保持亮屏的时长（分钟），到时自动收摊 */
        const val KEEP_SCREEN_MINUTES = 30L

        /** 无保活场景下，短暂保持前台保证目标 App 启动过渡完成的时长 */
        const val TRANSITION_MS = 3_000L

        /** 亮屏锁续命间隔：ROM 若剥离无人值守 App 的屏幕唤醒锁，周期性重持兜底 */
        const val RENEW_INTERVAL_MS = 60_000L
    }

    private val handler = Handler(Looper.getMainLooper())
    private var wakeLock: PowerManager.WakeLock? = null
    private var holdingScreen = false

    /** 用户按电源键主动熄屏 = 明确的停止信号：立即释放亮屏锁并收摊 */
    private val screenOffReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (Intent.ACTION_SCREEN_OFF == intent?.action) {
                Log.d("SP-Alarm", "UrlBridgeService: screen off -> stop early")
                stopBridge()
            }
        }
    }

    /** 亮屏锁续命：ROM 可能剥离无人值守 App 的屏幕唤醒锁，每分钟自查、掉了重持 */
    private val renewRunnable: Runnable = object : Runnable {
        override fun run() {
            val wl = wakeLock
            if (wl != null) {
                try {
                    if (!wl.isHeld) {
                        wl.acquire(KEEP_SCREEN_MINUTES * 60_000L)
                        Log.d("SP-Alarm", "UrlBridgeService re-acquired screen wake lock")
                    }
                } catch (e: Exception) {
                    Log.e("SP-Alarm", "wakeLock re-acquire failed: $e")
                }
            }
            handler.postDelayed(this, RENEW_INTERVAL_MS)
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // 常驻通知的"提前结束"入口：释放亮屏锁并收摊
        if (ACTION_STOP == intent?.action) {
            stopBridge()
            return START_NOT_STICKY
        }

        val raw = intent?.getStringExtra(EXTRA_URL) ?: ""
        val url = PlaybackReceiver.extractUrlCompat(raw)
        if (url.isEmpty()) {
            Log.e("SP-Alarm", "UrlBridgeService: url empty")
            stopSelf()
            return START_NOT_STICKY
        }
        Log.d("SP-Alarm", "UrlBridgeService onStartCommand, url=$url")

        // 亮屏保活的适用判定（2026-10-25 根因修正）：触发时屏幕熄灭 = 无人值守，
        // 必须持亮屏锁替 B站兜底。不再依据 isKeyguardLocked/isKeyguardSecure——
        // HyperOS 的 isKeyguardSecure() 会误报（2026-10-25 08:00 电池历史实锤：
        // 滑动锁、B站正常前台播放，但 keepScreen=false，亮屏 12 秒后熄屏、
        // 播放 19 秒即中断）。keyguard 两标志仅保留在日志里供诊断。
        // 触发时屏幕已亮（用户在场）：无需保活，维持原 3 秒过渡收摊。
        val km = getSystemService(Context.KEYGUARD_SERVICE) as? KeyguardManager
        val pmNow = getSystemService(Context.POWER_SERVICE) as? PowerManager
        val interactive = pmNow?.isInteractive ?: true
        val keyguardLocked = km?.isKeyguardLocked ?: false
        val keyguardSecure = km?.isKeyguardSecure ?: false
        val keepScreen = !interactive
        Log.d("SP-Alarm", "UrlBridgeService keepScreen=$keepScreen (interactive=$interactive, locked=$keyguardLocked, secure=$keyguardSecure)")

        // 立即转前台：进程状态变 FOREGROUND_SERVICE
        startForegroundCompat(buildNotification(keepScreen))

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
            handler.postDelayed({ stopSelf() }, TRANSITION_MS)
            return START_NOT_STICKY
        }

        if (keepScreen) {
            holdScreenOn()
        } else {
            // 短暂保持前台保证启动过渡完成，然后收摊（通知随之消失）
            handler.postDelayed({ stopSelf() }, TRANSITION_MS)
        }
        return START_NOT_STICKY
    }

    /**
     * 持亮屏锁并常驻前台，直到超时 / 用户熄屏 / 点通知结束。
     * acquire(timeout) 是兜底双保险：即使后续收摊逻辑因 ROM 怪癖没执行，
     * 到时也会自动释放，绝不会永久霸占亮屏。
     */
    private fun holdScreenOn() {
        holdingScreen = true
        val pm = getSystemService(Context.POWER_SERVICE) as? PowerManager
        if (pm != null) {
            try {
                // SCREEN_BRIGHT 系列 API 已废弃但持续有效，且是第三方 App
                // 替其他 App（B站）保持亮屏的唯一手段
                @Suppress("DEPRECATION")
                val wl = pm.newWakeLock(
                    PowerManager.SCREEN_BRIGHT_WAKE_LOCK or PowerManager.ACQUIRE_CAUSES_WAKEUP,
                    "scheduledplayer:url_keep_screen"
                )
                wl.setReferenceCounted(false)
                wl.acquire(KEEP_SCREEN_MINUTES * 60_000L)
                wakeLock = wl
                Log.d("SP-Alarm", "UrlBridgeService holding screen for $KEEP_SCREEN_MINUTES min")
            } catch (e: Exception) {
                Log.e("SP-Alarm", "wakeLock acquire failed: $e")
            }
        }
        val filter = IntentFilter(Intent.ACTION_SCREEN_OFF)
        if (Build.VERSION.SDK_INT >= 33) {
            registerReceiver(screenOffReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            registerReceiver(screenOffReceiver, filter)
        }
        handler.postDelayed(renewRunnable, RENEW_INTERVAL_MS)
        handler.postDelayed({ stopBridge() }, KEEP_SCREEN_MINUTES * 60_000L)
    }

    /** 统一收摊：撤定时器、释放亮屏锁、注销接收器、停服务 */
    private fun stopBridge() {
        handler.removeCallbacksAndMessages(null)
        releaseWakeLock()
        if (holdingScreen) {
            holdingScreen = false
            try {
                unregisterReceiver(screenOffReceiver)
            } catch (e: IllegalArgumentException) {
                // 未注册时注销属正常情况，忽略
            }
        }
        stopSelf()
    }

    private fun releaseWakeLock() {
        val wl = wakeLock ?: return
        try {
            if (wl.isHeld) {
                wl.release()
            }
        } catch (e: Exception) {
            Log.d("SP-Alarm", "releaseWakeLock failed: $e")
        }
        wakeLock = null
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        releaseWakeLock()
        if (holdingScreen) {
            holdingScreen = false
            try {
                unregisterReceiver(screenOffReceiver)
            } catch (e: IllegalArgumentException) {
                // 同 stopBridge：未注册时忽略
            }
        }
        super.onDestroy()
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

    @Suppress("DEPRECATION")
    private fun buildNotification(keepScreen: Boolean): Notification {
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
        // 亮屏保活期间：点通知 = 提前结束（释放亮屏锁收摊）；
        // 其余场景保持原样：点通知回 App 主页。
        val contentIntent: PendingIntent
        if (keepScreen) {
            contentIntent = PendingIntent.getService(
                this, 1,
                Intent(this, UrlBridgeService::class.java).setAction(ACTION_STOP),
                piFlags
            )
        } else {
            contentIntent = PendingIntent.getActivity(
                this, 0,
                Intent(this, MainActivity::class.java),
                piFlags
            )
        }
        val builder = if (Build.VERSION.SDK_INT >= 26) {
            Notification.Builder(this, CHANNEL_ID)
        } else {
            Notification.Builder(this)
        }
        return builder
            .setSmallIcon(android.R.drawable.ic_menu_share)
            .setContentTitle("定时任务")
            .setContentText(
                if (keepScreen) "正在自动播放，屏幕保持亮起（点按提前结束）"
                else "正在打开链接…"
            )
            .setOngoing(true)
            .setContentIntent(contentIntent)
            .build()
    }
}
