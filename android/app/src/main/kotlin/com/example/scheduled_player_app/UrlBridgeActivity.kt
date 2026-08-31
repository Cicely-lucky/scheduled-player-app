package com.example.scheduled_player_app

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.util.Log

/**
 * 网址任务闹钟的透明中转 Activity（BAL 直达链路的最后一段）。
 *
 * 为什么需要它：Android 14+ 的 BAL 豁免只对"PendingIntent 创建者显式
 * opt-in（ActivityOptions.MODE_BACKGROUND_ACTIVITY_START_ALLOWED）且通过
 * 该 PendingIntent 启动的 Activity"生效。getBroadcast 没有 opts 重载、
 * 无法 opt-in，因此"闹钟广播接收者里直接 startActivity"必然被 BAL_BLOCK
 * ——14:15 实测铁证：balRequireOptInByPendingIntentCreator=true 时
 * resultIfPiCreatorAllowsBal=BAL_BLOCK，即使 setAlarmClock 且
 * backgroundActivityAllowed=2 也照样拦截。
 *
 * 系统闹钟 App 的机制：setAlarmClock 的 PendingIntent 用 getActivity +
 * opts（MODE_BACKGROUND_ACTIVITY_START_ALLOWED）指向自己的全屏 Activity，
 * 到点系统以"用户可见"方式启动它。本类就是"我们的那个 Activity"——
 * 透明、无 UI，onCreate 里打开目标网址后立即 finish，
 * 用户感知为"到点直接弹出 B 站/浏览器"。
 */
class UrlBridgeActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val raw = intent.getStringExtra("url") ?: ""
        val url = PlaybackReceiver.extractUrlCompat(raw)
        Log.d("SP-Alarm", "bridge onCreate, raw=$raw")
        Log.d("SP-Alarm", "bridge extracted=$url")

        if (url.isEmpty()) {
            Log.e("SP-Alarm", "bridge: url empty, finish")
            finish()
            return
        }
        // 与 PlaybackReceiver 共享去重：Dart 二次广播兜底到达时不再重复打开
        if (!PlaybackReceiver.shouldOpen(url)) {
            Log.d("SP-Alarm", "bridge skip (opened recently)")
            finish()
            return
        }

        try {
            val view = Intent(Intent.ACTION_VIEW, Uri.parse(url))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            if (view.resolveActivity(packageManager) == null) {
                Log.e("SP-Alarm", "bridge: no activity can handle url: $url")
                PlaybackReceiver.showUrlFallbackNotification(this, url)
                finish()
                return
            }
            startActivity(view)
            Log.d("SP-Alarm", "bridge startActivity ok: $url")
        } catch (e: Exception) {
            Log.e("SP-Alarm", "bridge startActivity failed: $e")
            PlaybackReceiver.showUrlFallbackNotification(this, url)
        }
        finish()
    }
}
