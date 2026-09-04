package com.cici.scheduledplayer

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.util.Log

/**
 * 网址任务的原生精确闹钟。
 *
 * 为什么需要它：Android 14+（targetSdk 34）禁止后台启动 Activity（BAL），
 * 而 Dart 侧定时器在 App 进程存活时可用，进程被杀后必须靠系统闹钟唤醒。
 *
 * 载体演进（小米 Android 16 实测）：
 * 1) getBroadcast → receiver 内直接 startActivity：被拦（14:15 实测，
 *    procState=RECEIVER，BAL_BLOCK code=102）；
 * 2) getActivity + opts（MODE_BACKGROUND_ACTIVITY_START_ALLOWED）直启
 *    UrlBridgeActivity：仍被拦（15:50 / 16:00 两次实测，
 *    balAllowedByPiCreator=ALLOW_BAL 但 balAllowedByPiSender=NONE，
 *    发送者 system uid 未 opt-in，BAL_BLOCK code=102）；
 * 3) 【当前】getBroadcast → receiver → startForegroundService(UrlBridgeService)
 *    → 进程变 FOREGROUND_SERVICE（后台启动 Activity 的文档豁免场景）
 *    → 服务内 startActivity。广播只是可靠唤醒点，真正打开在服务里。
 *
 * 广播接收者能启动前台服务：精确闹钟（setAlarmClock）触发后 App 处于
 * 系统临时白名单（temporaryAppAllowlistDuration=10000），Android 12+
 * 的后台前台服务限制对该场景豁免。
 *
 * 同步协议：Dart 侧（主 isolate / 后台 isolate 均可）发 SYNC_URL_ALARMS
 * 广播携带全量 JSON 列表，本端负责增删改（差异取消旧闹钟），
 * 持久化已注册 id 集合用于清理失效闹钟。
 */
object UrlAlarmScheduler {

    private const val PREFS = "url_alarm_state"
    private const val KEY_IDS = "scheduled_ids"
    const val ACTION_OPEN = "com.cici.scheduledplayer.OPEN_URL"

    data class Item(val id: Int, val at: Long, val url: String)

    /** 全量同步：取消不再需要的闹钟，注册/刷新新闹钟 */
    fun sync(context: Context, items: List<Item>) {
        val am = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val old = prefs.getStringSet(KEY_IDS, emptySet())
            ?.mapNotNull { it.toIntOrNull() }?.toSet() ?: emptySet()
        val newIds = items.map { it.id }.toSet()

        var cancelled = 0
        for (id in old) {
            if (id !in newIds) {
                cancelInternal(context, am, id)
                cancelled++
            }
        }
        for (item in items) scheduleInternal(context, am, item)
        prefs.edit()
            .putStringSet(KEY_IDS, newIds.map { it.toString() }.toSet())
            .apply()
        Log.d("SP-Alarm", "url alarms synced: ${items.size} scheduled, $cancelled cancelled")
    }

    private fun scheduleInternal(context: Context, am: AlarmManager, item: Item) {
        // 广播到 PlaybackReceiver：到点唤醒进程，receiver 内再经前台服务中转打开。
        // 注意：不能 getActivity 直启 Activity——小米 Android 16 实测即使
        // getActivity+opts 显式 opt-in（balAllowedByPiCreator=ALLOW_BAL），
        // 发送者 system uid 未 opt-in（balAllowedByPiSender=NONE）仍被
        // BAL_BLOCK（result code=102），Activity 根本不会创建。
        val fire = Intent(context, PlaybackReceiver::class.java).apply {
            action = ACTION_OPEN
            putExtra("url", item.url)
            putExtra("from_native_alarm", true)
        }
        val flags = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        val op = PendingIntent.getBroadcast(context, item.id, fire, flags)
        // 状态栏闹钟图标的点击意图：回 App
        val show = PendingIntent.getActivity(
            context, item.id,
            context.packageManager.getLaunchIntentForPackage(context.packageName),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        am.setAlarmClock(AlarmManager.AlarmClockInfo(item.at, show), op)
        Log.d("SP-Alarm", "native url alarm #${item.id} scheduled at ${item.at}: ${item.url}")
    }

    private fun cancelInternal(context: Context, am: AlarmManager, id: Int) {
        val fire = Intent(context, PlaybackReceiver::class.java).apply { action = ACTION_OPEN }
        val op = PendingIntent.getBroadcast(
            context, id, fire,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        am.cancel(op)
        op.cancel()
        Log.d("SP-Alarm", "native url alarm #$id cancelled")
    }
}
