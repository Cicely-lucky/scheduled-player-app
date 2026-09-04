package com.cici.scheduledplayer

import android.app.Activity
import android.app.KeyguardManager
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 网址任务自动打开的锁屏可见中转 Activity。
 *
 * 演进史（实测驱动）：
 * 1. UrlBridgeService 内 startActivity 被 BAL 拦截 → 悬浮窗权限豁免解决。
 * 2. MIUI KeyguardLocked 拦截 → 本 Activity 带 showWhenLocked + turnScreenOn
 *    （系统闹钟同款机制），成功在锁屏之上显示并点亮屏幕。
 * 3. 12:58 实测新问题：B站 IntentHandlerActivity 自身没有 showWhenLocked
 *    （canShowWhenLocked:false），成为栈顶后系统立即撤掉锁屏遮挡并恢复
 *    休眠（occludedChanged mOccluded=false → Going to sleep），屏幕亮起
 *    200ms 即灭，B站被压在锁屏之下不可见。
 *
 * 第 3 个问题的修复尝试与结论：
 * - FLAG_ACTIVITY_SHOW_WHEN_LOCKED / FLAG_ACTIVITY_TURN_SCREEN_ON intent
 *   flag 与 Activity.setDismissKeyguard 均已从新版 SDK 移除（CI 编译失败：
 *   Unresolved reference），无法把 showWhenLocked 强加给第三方 Activity。
 * - 唯一可行路径：KeyguardManager.requestDismissKeyguard（API 26+，现行）
 *   - 非安全锁（无/滑动解锁）：锁屏被直接解除，B站全屏正常显示，最理想；
 *   - 安全锁（PIN/指纹）：系统弹出解锁界面（无法也不应绕过），用户解锁后
 *     直接看到已加载完成的B站播放页。
 *
 * 流程：onCreate → 点亮屏幕+盖在锁屏上 → 请求解除锁屏 → 回调（或超时
 * 兜底）后打开深链 → 延迟 2.5s finish（给目标 App 绘制时间）。
 */
class UrlBridgeActivity : Activity() {

    companion object {
        const val EXTRA_URL = "url"
        private const val KEYGUARD_WAIT_MS = 1_500L
        private const val FINISH_DELAY_MS = 2_500L
    }

    /** openAndFinish 只执行一次（回调 + 超时兜底可能都触发） */
    private val opened = AtomicBoolean(false)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // 系统闹钟同款：锁屏之上显示 + 点亮屏幕（API 27+）
        if (Build.VERSION.SDK_INT >= 27) {
            setShowWhenLocked(true)
            setTurnScreenOn(true)
        }
        val raw = intent.getStringExtra(EXTRA_URL) ?: ""
        val url = PlaybackReceiver.extractUrlCompat(raw)
        Log.d("SP-Alarm", "UrlBridgeActivity onCreate, url=$url")

        requestKeyguardDismissThenOpen(url)
        // 兜底：回调超时/不回调也照常打开（安全锁时B站加载在解锁界面之后）
        Handler(Looper.getMainLooper())
            .postDelayed({ openAndFinish(url) }, KEYGUARD_WAIT_MS)
    }

    /**
     * 请求解除锁屏后打开目标链接。
     * 非安全锁：onDismissSucceeded → 解锁完成再打开，B站直接全屏；
     * 安全锁：onDismissCancelled/系统弹解锁界面 → 仍打开B站（加载在
     * 解锁界面之下，用户解锁后即见）。
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
        // 延迟收摊：保持本窗口盖住锁屏，等目标 App 完成启动绘制
        Handler(Looper.getMainLooper()).postDelayed({ finish() }, FINISH_DELAY_MS)
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
                // 09-04 08:58 实测问题：B站旧任务仍留在后台时，深链只是把
                // 旧任务拉到前台并恢复上次会话——播放的是退出B站时的旧视频，
                // 定时链接被吞掉。修复：深链加 FLAG_ACTIVITY_CLEAR_TASK，
                // 清掉B站旧任务、强制冷启动到目标视频页（个人自用可接受打断
                // B站后台播放）。网页链接的浏览器回退不加此 flag，避免清空
                // 用户浏览器会话。
                if (isBiliDeepLink) {
                    view.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TASK)
                }
                val resolved = view.resolveActivity(packageManager)
                if (resolved == null || resolved.packageName == "android" ||
                    resolved.className.contains("ResolverActivity")
                ) {
                    Log.d("SP-Alarm", "UrlBridgeActivity: skip $target (resolver=$resolved)")
                    continue
                }
                startActivity(view)
                Log.d("SP-Alarm", "UrlBridgeActivity startActivity ok: $target -> ${resolved.packageName}")
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
     * 仅转换 www.bilibili.com 的 /video/BVxxx 路径；b23.tv 短链与其他链接原样返回 null。
     */
    private fun toBilibiliDeepLink(url: String): String? {
        val m = Regex("^https?://www\\.bilibili\\.com/video/(BV[0-9A-Za-z]+)(?:\\?.*)?$").find(url)
            ?: return null
        val bv = m.groupValues[1]
        val p = Regex("[?&]p=(\\d+)").find(url)?.groupValues?.get(1)
        return buildString {
            append("bilibili://video/").append(bv)
            if (p != null) append("?p=").append(p)
        }
    }
}
