package com.example.scheduled_player_app

import android.app.ActivityOptions
import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log

/**
 * 网址任务的原生精确闹钟（BAL 直达链路）。
 *
 * 为什么需要它：Android 14+（targetSdk 34）禁止后台启动 Activity（BAL）。
 * 14:15 实测铁证：闹钟广播的直接接收者里 startActivity 仍被拦——
 * "balRequireOptInByPendingIntentCreator=true 时
 * resultIfPiCreatorAllowsBal=BAL_BLOCK"。getBroadcast 没有 opts 重载、
 * 无法声明 MODE_BACKGROUND_ACTIVITY_START_ALLOWED，所以广播路径
 * 永远无法 opt-in，receiver 内 startActivity 必被 AOSP 静默拦截。
 *
 * 正确机制（与系统闹钟 App 到点弹响铃界面完全同构）：setAlarmClock 的
 * PendingIntent 用 getActivity + opts（API 34+ 的
 * setPendingIntentBackgroundActivityStartMode(ALLOWED)）直接指向
 * UrlBridgeActivity——透明中转 Activity。到点系统以"用户可见"方式启动
 * 该 Activity（BAL 放行），它在 onCreate 里打开目标网址后立即 finish，
 * 用户感知为"到点直接弹出 B 站/浏览器"。
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
        // 直达 UrlBridgeActivity：闹钟到点由系统以用户可见方式启动它
        // （getActivity + opts 显式声明后台启动豁免，API 34+）。
        // 注意：不能 getBroadcast——没有 opts 重载，无法 opt-in，
        // receiver 内 startActivity 必被 BAL_BLOCK（14:15 实测）。
        val fire = Intent(context, UrlBridgeActivity::class.java).apply {
            action = ACTION_OPEN
            putExtra("url", item.url)
            putExtra("from_native_alarm", true)
        }
        val flags = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        val op: PendingIntent = if (Build.VERSION.SDK_INT >= 34) {
            val opts = ActivityOptions.makeBasic()
                .setPendingIntentBackgroundActivityStartMode(
                    ActivityOptions.MODE_BACKGROUND_ACTIVITY_START_ALLOWED
                )
                .toBundle()
            PendingIntent.getActivity(context, item.id, fire, flags, opts)
        } else {
            PendingIntent.getActivity(context, item.id, fire, flags)
        }
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
        val fire = Intent(context, UrlBridgeActivity::class.java).apply { action = ACTION_OPEN }
        val op = PendingIntent.getActivity(
            context, id, fire,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        am.cancel(op)
        op.cancel()
        Log.d("SP-Alarm", "native url alarm #$id cancelled")
    }
}
