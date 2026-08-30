package com.example.scheduled_player_app

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.util.Log

/**
 * 网址任务的原生精确闹钟（BAL 直达链路）。
 *
 * 为什么需要它：Android 14+（targetSdk 34）禁止后台启动 Activity（BAL），
 * 闹钟触发的 BAL 豁免只授予闹钟广播的【直接接收者】。此前链路是
 * "闹钟 → 插件 Receiver → Dart 回调 → 二次广播 → PlaybackReceiver"，
 * 豁免在二次广播处已丢失，startActivity 被 AOSP 静默拦截
 * （日志：Background activity launch blocked, result code=102）。
 *
 * 本类为网址任务单独注册 setAlarmClock 闹钟，PendingIntent 直接指向
 * PlaybackReceiver，且创建时带 MODE_BACKGROUND_ACTIVITY_START_ALLOWED
 * ——与系统闹钟 App 到点弹响铃界面完全同机制，receiver 内 startActivity
 * 不再被拦截。
 *
 * 同步协议：Dart 侧（主 isolate / 后台 isolate 均可）发 SYNC_URL_ALARMS
 * 广播携带全量 JSON 列表，本端负责增删改（差异取消旧闹钟），
 * 持久化已注册 id 集合用于清理失效闹钟。
 */
object UrlAlarmScheduler {

    private const val PREFS = "url_alarm_state"
    private const val KEY_IDS = "scheduled_ids"
    const val ACTION_OPEN = "com.example.scheduled_player_app.OPEN_URL"

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
        val fire = Intent(context, PlaybackReceiver::class.java).apply {
            action = ACTION_OPEN
            putExtra("url", item.url)
            putExtra("from_native_alarm", true)
        }
        val flags = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        // 注意：PendingIntent.getBroadcast 没有 (Context,Int,Intent,Int,Bundle)
        // 重载——Bundle opts 仅 getActivity/getActivities 支持，上一版传了
        // 5 个参数导致 Kotlin 编译失败（CI run 33311517826 构建失败根因）。
        // BAL 豁免也不需要 opts：setAlarmClock 的闹钟广播【直接接收者】
        // 自动获得后台启动 Activity 豁免（系统闹钟 App 到点弹响铃界面
        // 正是此机制），无需 ActivityOptions 声明。
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
