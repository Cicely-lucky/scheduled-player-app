package com.cici.scheduledplayer

import android.app.Activity
import android.app.KeyguardManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Color
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.Gravity
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.TextView
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 网址任务自动打开的锁屏可见中转 Activity。
 *
 * 演进史（实测驱动）：
 * 1. UrlBridgeService 内 startActivity 被 BAL 拦截 → 悬浮窗权限豁免解决。
 * 2. MIUI KeyguardLocked 拦截 → 本 Activity 带 showWhenLocked + turnScreenOn
 *    （系统闹钟同款机制），成功在锁屏之上显示并点亮屏幕。
 * 3. B站 IntentHandlerActivity 自身没有 showWhenLocked，成为栈顶后系统
 *    撤掉锁屏遮挡并恢复休眠，屏幕亮起即灭 → 引入 requestDismissKeyguard。
 * 4. 2026-10-25 11:41 实测（logcat 实锤，本轮根因）：
 *    a) HyperOS 把第三方 SCREEN_BRIGHT_WAKE_LOCK 在获取 ~7 秒后标记
 *       DISABLED（PowerManagerServiceImpl dump 原文），无人触屏的程序化
 *       亮屏 ~12 秒后仍被强制熄灭进 AOD——唤醒锁路线在 MIUI 上证伪；
 *    b) Manifest 里 noHistory="true" 会在 B站任务切到前台的 ~0.5 秒内把本
 *       Activity 悄悄 finish（finish 时间戳早于自身 2.5s 定时），保活窗口
 *       根本活不到设计时长。
 *    修复：noHistory 移除；keepAlive 模式下本 Activity 不再 2.5 秒收摊，
 *    而是作为「面纱」常驻锁屏之上，靠窗口 FLAG_KEEP_SCREEN_ON（可见窗口
 *    请求保屏，系统闹钟同款、ROM 不敢动的机制）替 B站保亮屏。B站在锁屏
 *    遮挡下照常起播（10-25 08:00 电池历史实锤：音频视频均在播，熄屏才停）。
 *    面纱收摊信号：用户解锁（ACTION_USER_PRESENT）/ 电源键熄屏 /
 *    30 分钟超时 / 服务收摊广播（点通知提前结束等）/ 返回键。
 *
 * 流程：onCreate → 点亮屏幕+盖在锁屏上 → 请求解除锁屏 → 回调（或超时
 * 兜底）后打开深链 → keepAlive 时面纱常驻保屏，否则 2.5s 后 finish。
 */
class UrlBridgeActivity : Activity() {

    companion object {
        const val EXTRA_URL = "url"

        /** 无人值守保活模式：本窗口作为「面纱」常驻锁屏之上替 B站保亮屏 */
        const val EXTRA_KEEP_ALIVE = "keepAlive"

        /** 服务收摊时通知面纱一并结束的同 App 广播（setPackage 定向发送） */
        const val ACTION_VEIL_FINISH = "com.cici.scheduledplayer.URL_BRIDGE_FINISH"

        private const val KEYGUARD_WAIT_MS = 1_500L
        private const val FINISH_DELAY_MS = 2_500L
    }

    /** openAndFinish 只执行一次（回调 + 超时兜底可能都触发） */
    private val opened = AtomicBoolean(false)

    private val handler = Handler(Looper.getMainLooper())
    private var keepAlive = false
    private var veilReceiverRegistered = false

    /** 面纱收摊信号：用户解锁到场 / 电源键熄屏 / 服务广播 */
    private val veilReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                Intent.ACTION_USER_PRESENT -> {
                    Log.d("SP-Alarm", "UrlBridge veil: user present -> finish")
                    finish()
                }
                Intent.ACTION_SCREEN_OFF -> {
                    Log.d("SP-Alarm", "UrlBridge veil: screen off -> finish")
                    finish()
                }
                ACTION_VEIL_FINISH -> {
                    Log.d("SP-Alarm", "UrlBridge veil: stop broadcast -> finish")
                    finish()
                }
            }
        }
    }

    /** 面纱超时上限（与服务 KEEP_SCREEN_MINUTES 对齐，防所有收摊信号全失效） */
    private val veilTimeoutRunnable = Runnable {
        Log.d("SP-Alarm", "UrlBridge veil: timeout -> finish")
        finish()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // 系统闹钟同款：锁屏之上显示 + 点亮屏幕（API 27+）
        if (Build.VERSION.SDK_INT >= 27) {
            setShowWhenLocked(true)
            setTurnScreenOn(true)
        }
        // 2026-10-25 11:41 实测：HyperOS 把第三方 SCREEN_BRIGHT_WAKE_LOCK 在
        // ~7 秒后标记 DISABLED，无人触屏的程序化亮屏 ~12 秒仍被强制熄灭——
        // 唤醒锁路线证伪。改由本窗口（锁屏之上持续可见）的 FLAG_KEEP_SCREEN_ON
        // 保亮屏：可见窗口请求保屏，系统闹钟同款机制。
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        keepAlive = intent.getBooleanExtra(EXTRA_KEEP_ALIVE, false)
        val raw = intent.getStringExtra(EXTRA_URL) ?: ""
        val url = PlaybackReceiver.extractUrlCompat(raw)
        Log.d("SP-Alarm", "UrlBridgeActivity onCreate, url=$url, keepAlive=$keepAlive")

        if (keepAlive) {
            buildVeilUi()
            registerVeilReceiver()
            handler.postDelayed(
                veilTimeoutRunnable,
                UrlBridgeService.KEEP_SCREEN_MINUTES * 60_000L
            )
            Log.d("SP-Alarm", "UrlBridge keep-alive veil shown (FLAG_KEEP_SCREEN_ON)")
        }

        requestKeyguardDismissThenOpen(url)
        // 兜底：回调超时/不回调也照常打开（安全锁时B站加载在解锁界面之后）
        handler.postDelayed({ openAndFinish(url) }, KEYGUARD_WAIT_MS)
    }

    /**
     * 面纱 UI：半透明黑纱盖在锁屏之上（锁屏时钟透出可见），中央提示播放状态。
     * 点击任意处 → requestDismissKeyguard（安全锁弹系统解锁界面，非安全锁
     * 直接解除），解锁成功后 ACTION_USER_PRESENT 触发收摊、B站全屏接管。
     */
    private fun buildVeilUi() {
        val minutes = UrlBridgeService.KEEP_SCREEN_MINUTES
        val root = FrameLayout(this)
        root.setBackgroundColor(Color.argb(0xB3, 0x00, 0x00, 0x00))
        val tv = TextView(this).apply {
            text = "定时播放中\n\nB站在锁屏之后继续播放\n点击任意位置解锁查看\n\n$minutes 分钟后自动结束"
            setTextColor(Color.WHITE)
            textSize = 16f
            gravity = Gravity.CENTER
            setLineSpacing(0f, 1.4f)
        }
        root.addView(
            tv,
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.CENTER
            )
        )
        setContentView(root)
        root.setOnClickListener { dismissKeyguardFromVeilTap() }
    }

    private fun dismissKeyguardFromVeilTap() {
        val km = getSystemService(KEYGUARD_SERVICE) as? KeyguardManager
        if (km == null) {
            Log.d("SP-Alarm", "veil tap: KeyguardManager unavailable")
            return
        }
        try {
            km.requestDismissKeyguard(this, object : KeyguardManager.KeyguardDismissCallback() {
                override fun onDismissError() {
                    Log.d("SP-Alarm", "veil tap dismiss error")
                }

                override fun onDismissCancelled() {
                    Log.d("SP-Alarm", "veil tap dismiss cancelled")
                }
            })
            Log.d("SP-Alarm", "veil tap -> requestDismissKeyguard")
        } catch (e: Exception) {
            Log.d("SP-Alarm", "veil tap dismiss failed: $e")
        }
    }

    private fun registerVeilReceiver() {
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_USER_PRESENT)
            addAction(Intent.ACTION_SCREEN_OFF)
            addAction(ACTION_VEIL_FINISH)
        }
        if (Build.VERSION.SDK_INT >= 33) {
            registerReceiver(veilReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            registerReceiver(veilReceiver, filter)
        }
        veilReceiverRegistered = true
    }

    /**
     * 请求解除锁屏后打开目标链接。
     * 非安全锁：onDismissSucceeded → 解锁完成再打开，B站直接全屏；
     * 安全锁：onDismissCancelled/系统弹解锁界面 → 仍打开B站（加载在
     * 解锁界面之下，keepAlive 面纱保屏，用户解锁后即见）。
     */
    private fun requestKeyguardDismissThenOpen(url: String) {
        val km = getSystemService(KEYGUARD_SERVICE) as? KeyguardManager
        if (km == null) {
            openAndFinish(url)
            return
        }
        val callback = object : KeyguardManager.KeyguardDismissCallback() {
            override fun onDismissSucceeded() {
                Log.d("SP-Alarm", "keyguard dismissed -> open url")
                openAndFinish(url)
            }

            override fun onDismissError() {
                Log.d("SP-Alarm", "keyguard dismiss error -> open url anyway")
                openAndFinish(url)
            }

            override fun onDismissCancelled() {
                Log.d("SP-Alarm", "keyguard dismiss cancelled -> open url anyway")
                openAndFinish(url)
            }
        }
        try {
            km.requestDismissKeyguard(this, callback)
            Log.d("SP-Alarm", "requestDismissKeyguard sent (secure=${km.isKeyguardSecure})")
        } catch (e: Exception) {
            Log.d("SP-Alarm", "requestDismissKeyguard failed: $e")
            openAndFinish(url)
        }
    }

    private fun openAndFinish(url: String) {
        if (!opened.compareAndSet(false, true)) return
        if (url.isNotEmpty()) {
            openUrl(url)
        }
        if (keepAlive) {
            // 保活模式：面纱常驻锁屏之上替 B站保亮屏；
            // 收摊交给 USER_PRESENT / SCREEN_OFF / 超时 / 服务广播。
            Log.d("SP-Alarm", "UrlBridge veil keep-alive active")
        } else {
            // 延迟收摊：保持本窗口盖住锁屏，等目标 App 完成启动绘制
            handler.postDelayed({ finish() }, FINISH_DELAY_MS)
        }
    }

    @Suppress("DEPRECATION")
    override fun onBackPressed() {
        // 返回键 = 用户显式结束面纱（随后 B站将随锁屏熄屏暂停）
        Log.d("SP-Alarm", "UrlBridge veil: back pressed -> finish")
        finish()
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        if (veilReceiverRegistered) {
            veilReceiverRegistered = false
            try {
                unregisterReceiver(veilReceiver)
            } catch (e: IllegalArgumentException) {
                // 未注册时注销属正常情况，忽略
            }
        }
        super.onDestroy()
    }

    private fun openUrl(url: String): Boolean {
        // B 站网页链接优先转深链：bilibili://video/BVxxx 直接命中 B 站 App
        // （需 Manifest <queries> 声明 bilibili scheme，Android 11+ 包可见性限制，
        // 否则 resolveActivity 返回 null 看不到 tv.danmaku.bili——17:25 实测教训）。
        val candidates = mutableListOf<String>()
        toBilibiliDeepLink(url)?.let { candidates.add(it) }
        candidates.add(url)

        for (target in candidates) {
            try {
                val isBiliDeepLink = target.startsWith("bilibili://")
                val view = Intent(Intent.ACTION_VIEW, Uri.parse(target))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                val resolved = view.resolveActivity(packageManager)
                if (resolved == null || resolved.packageName == "android" ||
                    resolved.className.contains("ResolverActivity")
                ) {
                    Log.d("SP-Alarm", "UrlBridgeActivity: skip $target (resolver=$resolved)")
                    continue
                }
                // 09-04 08:58 实测问题：B站旧任务仍留在后台时，深链只是把
                // 旧任务拉到前台并恢复上次会话——播放的是退出B站时的旧视频，
                // 定时链接被吞掉。修复：深链加 FLAG_ACTIVITY_CLEAR_TASK，
                // 清掉B站旧任务、强制冷启动到目标视频页（个人自用可接受打断
                // B站后台播放）。
                //
                // 09-12 10:20 实测问题（本轮修复）：B站对 b23.tv / bilibili.com
                // 注册了 App Links，b23.tv 短链等未走深链转换的链接同样会被
                // B站接住——但那条路径没有加 CLEAR_TASK，到点只是恢复B站旧会话，
                // 播的还是旧视频。修复：不再只看"是不是深链"，而是看**接收方
                // 是不是B站 App**——凡是落到 B站包名的 intent 一律 CLEAR_TASK，
                // 强制冷启动。浏览器等其他接收方不受影响。
                val isBiliApp = resolved.packageName == "tv.danmaku.bili" ||
                    resolved.packageName == "com.bilibili.app.in"
                if (isBiliDeepLink || isBiliApp) {
                    view.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TASK)
                }
                startActivity(view)
                Log.d("SP-Alarm", "UrlBridgeActivity startActivity ok: $target -> ${resolved.packageName} (clearTask=${isBiliDeepLink || isBiliApp})")
                return true
            } catch (e: Exception) {
                Log.d("SP-Alarm", "UrlBridgeActivity try $target failed: $e")
            }
        }
        Log.e("SP-Alarm", "UrlBridgeActivity: all candidates failed for $url")
        PlaybackReceiver.showUrlFallbackNotification(this, url)
        return false
    }

    /**
     * https://www.bilibili.com/video/BVxxx?t=1.4&p=48
     *   → bilibili://video/BVxxx?p=48
     * https://m.bilibili.com/video/BVxxx/（m 站、尾部斜杠、av 号同样支持）
     *   → bilibili://video/BVxxx
     * 仅转换 bilibili.com 域名的 /video/ 路径；b23.tv 短链无法离线还原 BV 号
     * （需网络 302 跳转），原样返回 null，由上面的"按包名 CLEAR_TASK"兜底。
     */
    private fun toBilibiliDeepLink(url: String): String? {
        val m = Regex(
            "^https?://(?:www|m)\\.bilibili\\.com/video/(BV[0-9A-Za-z]+|av\\d+)/?(?:\\?.*)?$"
        ).find(url) ?: return null
        val vid = m.groupValues[1]
        val p = Regex("[?&]p=(\\d+)").find(url)?.groupValues?.get(1)
        return buildString {
            append("bilibili://video/").append(vid)
            if (p != null) append("?p=").append(p)
        }
    }
}
