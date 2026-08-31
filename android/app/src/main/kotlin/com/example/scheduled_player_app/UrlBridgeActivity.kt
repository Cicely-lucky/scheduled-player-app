package com.example.scheduled_player_app

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log

/**
 * 网址任务自动打开的锁屏可见中转 Activity。
 *
 * 17:25 实测：UrlBridgeService 内 startActivity 的 BAL 判定已放行
 * （BAL_ALLOW_SAW_PERMISSION，悬浮窗豁免生效），但屏幕熄灭+锁屏时被
 * MIUI 的 KeyguardLocked 检查拦下（Permission Denied Activity KeyguardLocked，
 * result code=102）——第三方 App 的 Activity 不带 showWhenLocked 标志，
 * 系统不允许它在锁屏之上显示。
 *
 * 本 Activity 自带 setShowWhenLocked + setTurnScreenOn（系统闹钟同款机制）：
 * - 从 UrlBridgeService 拉起（悬浮窗豁免保证 BAL 放行）
 * - 锁屏上可见并点亮屏幕，此时它是最前台的可见 Activity
 * - onCreate 里打开目标链接（B 站深链优先），此时目标 Activity 在可见
 *   Activity 之上启动，不再触发锁屏拦截
 * - 延迟 2.5s 再 finish：保持本窗口在前台覆盖住锁屏，给目标 App 足够的
 *   绘制/启动时间；过早 finish 锁屏会重新盖上来
 */
class UrlBridgeActivity : Activity() {

    companion object {
        const val EXTRA_URL = "url"
        private const val FINISH_DELAY_MS = 2_500L
    }

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
        if (url.isNotEmpty()) {
            openUrl(url)
        }
        // 延迟收摊：保持窗口盖住锁屏，等目标 App 完成启动绘制
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
                val view = Intent(Intent.ACTION_VIEW, Uri.parse(target))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
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
