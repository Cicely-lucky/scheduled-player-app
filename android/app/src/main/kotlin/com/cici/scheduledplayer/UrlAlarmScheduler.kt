package com.cici.scheduledplayer

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.util.Log
import java.util.Calendar

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
    /** 任务完整数据（JSON 数组）：重启/覆盖安装/被系统清理后据此原地重建闹钟，
     *  不再依赖用户打开 App（此前只存了 id，重建无从下手，这是"用一段时间后
     *  不再定时播放、手动编辑保存才恢复"的根因之一）。 */
    private const val KEY_ITEMS = "scheduled_items"
    const val ACTION_OPEN = "com.cici.scheduledplayer.OPEN_URL"

    data class Item(
        val id: Int, val at: Long, val url: String,
        // 重复规则（与 Dart 端 PlayTask 一致）：触发后原生自行重排下一次
        val freq: String = "daily",   // daily | weekly | date
        val days: Set<Int> = emptySet(), // 1=周一 ... 7=周日
        val time: String = "08:00",   // "HH:mm"
        val date: String = ""         // freq=date 时的 "yyyy-MM-dd"
    )

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
        saveItems(context, items)
        Log.d("SP-Alarm", "url alarms synced: ${items.size} scheduled, $cancelled cancelled")
    }

    /**
     * 持久化任务完整数据（含重复规则），供重启/升级/被系统清理后原地重建。
     * 闹钟本身（AlarmManager）在"重启、覆盖安装、应用被强制停止、系统休眠回收"
     * 四种情况下都会被系统清空，只有把规则留下来才能自愈。
     */
    private fun saveItems(context: Context, items: List<Item>) {
        try {
            val arr = org.json.JSONArray()
            for (it in items) {
                val o = org.json.JSONObject()
                o.put("id", it.id)
                o.put("url", it.url)
                o.put("freq", it.freq)
                o.put("days", org.json.JSONArray(it.days.toList()))
                o.put("time", it.time)
                o.put("date", it.date)
                arr.put(o)
            }
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit().putString(KEY_ITEMS, arr.toString()).apply()
        } catch (e: Exception) {
            Log.e("SP-Alarm", "saveItems failed: $e")
        }
    }

    /** 读取持久化的任务规则（at 字段用 0 占位，真实时刻每次重算） */
    fun loadItems(context: Context): List<Item> {
        return try {
            val json = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .getString(KEY_ITEMS, null) ?: return emptyList()
            val arr = org.json.JSONArray(json)
            (0 until arr.length()).mapNotNull { i ->
                val o = arr.optJSONObject(i) ?: return@mapNotNull null
                val daysArr = o.optJSONArray("days")
                Item(
                    id = o.optInt("id", 0),
                    at = 0L,
                    url = o.optString("url", ""),
                    freq = o.optString("freq", "daily"),
                    days = if (daysArr == null) emptySet()
                    else (0 until daysArr.length()).map { daysArr.optInt(it) }.toSet(),
                    time = o.optString("time", "08:00"),
                    date = o.optString("date", "")
                )
            }.filter { it.id != 0 && it.url.isNotEmpty() }
        } catch (e: Exception) {
            Log.e("SP-Alarm", "loadItems failed: $e")
            emptyList()
        }
    }

    /**
     * 全量重建：按持久化规则重新计算下一次触发时刻并注册闹钟。
     * 调用时机（均不依赖用户打开 App）：
     * - 手机重启完成后（BOOT_COMPLETED）
     * - App 覆盖安装/更新后（MY_PACKAGE_REPLACED，安装新版本必清空闹钟）
     * - 系统时间/时区被修改后（原闹钟基于旧时刻，需重算）
     * - 精确闹钟权限被授予后（SCHEDULE_EXACT_ALARM_PERMISSION_STATE_CHANGED）
     * - 心跳自检发现闹钟缺失时（SelfHealJob）
     * @return 重建成功的闹钟数量
     */
    fun restoreAll(context: Context): Int {
        val items = loadItems(context)
        if (items.isEmpty()) {
            Log.d("SP-Alarm", "restoreAll: 无持久化任务，跳过")
            return 0
        }
        val am = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val now = Calendar.getInstance()
        val alive = mutableListOf<Item>()
        var ok = 0
        for (item in items) {
            val next = nextAtMillis(now, item) ?: continue   // 已过期的一次性任务丢弃
            scheduleInternal(context, am, item.copy(at = next))
            alive.add(item)
            ok++
            Log.d("SP-Alarm", "restoreAll: #${item.id} -> $next (${item.freq}/${item.time})")
        }
        // 回写存活的 id 集合与规则（清理过期项，避免残留空 id）
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putStringSet(KEY_IDS, alive.map { it.id.toString() }.toSet())
            .apply()
        saveItems(context, alive)
        Log.d("SP-Alarm", "restoreAll done: $ok/${items.size} alarms restored")
        return ok
    }

    /**
     * 心跳自检：逐条确认闹钟是否仍在系统里（PendingIntent 是否存在），
     * 缺失的立即补建；同时把"触发时刻已过但闹钟却没了"的异常情况纠正过来。
     * 覆盖"闹钟被 ROM 悄悄清掉、但 App 没有被强制停止"这类暗失效。
     * @return 补建数量
     */
    fun verifyAndFix(context: Context): Int {
        val items = loadItems(context)
        if (items.isEmpty()) return 0
        val am = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val now = Calendar.getInstance()
        var fixed = 0
        for (item in items) {
            val fire = Intent(context, PlaybackReceiver::class.java).apply {
                action = ACTION_OPEN
                putExtra("url", item.url)
            }
            val exists = PendingIntent.getBroadcast(
                context, item.id, fire,
                PendingIntent.FLAG_NO_CREATE or PendingIntent.FLAG_IMMUTABLE
            ) != null
            if (!exists) {
                val next = nextAtMillis(now, item) ?: continue
                scheduleInternal(context, am, item.copy(at = next))
                fixed++
                Log.d("SP-Alarm", "self-heal: #${item.id} 闹钟丢失，已补建 -> $next")
            }
        }
        if (fixed > 0) Log.d("SP-Alarm", "self-heal: 补建 $fixed 个闹钟")
        return fixed
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
            // 重复规则随闹钟下发：receiver 触发后据此自行重排下一次（自愈链）
            putExtra("alarm_id", item.id)
            putExtra("freq", item.freq)
            putExtra("days", item.days.toIntArray())
            putExtra("time", item.time)
            putExtra("date", item.date)
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

    // ------------------------------------------------------------------
    // 原生自愈链：闹钟触发后由 receiver 调用，自行重排下一次。
    // 此前重排完全依赖 Dart 后台 isolate（check() 末尾的 scheduleNext），
    // 进程被杀且拉起失败时链条即断（"播放一次后不再触发"的根因）。
    // 现在原生触发后自己算下一次，与 Dart 链互为备份、真正解耦。
    // ------------------------------------------------------------------

    /**
     * 计算重复规则的下一次触发时刻（与 Dart 端 PlayTask.nextRunAt 逻辑一致）。
     * 返回 null 表示不会再触发（freq=date 且日期已过）。
     */
    fun nextAtMillis(now: Calendar, item: Item): Long? {
        val parts = item.time.split(":")
        if (parts.size != 2) return null
        val h = parts[0].toIntOrNull() ?: return null
        val m = parts[1].toIntOrNull() ?: return null

        fun at(day: Calendar): Calendar = (day.clone() as Calendar).apply {
            set(Calendar.HOUR_OF_DAY, h)
            set(Calendar.MINUTE, m)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }

        when (item.freq) {
            "date" -> {
                val d = item.date.split("-")
                if (d.size != 3) return null
                val c = at(Calendar.getInstance().apply {
                    set(d[0].toIntOrNull() ?: return null,
                        (d[1].toIntOrNull() ?: return null) - 1,
                        d[2].toIntOrNull() ?: return null)
                })
                return if (c.timeInMillis > now.timeInMillis) c.timeInMillis else null
            }
            "weekly" -> {
                // 往后找 8 天，取第一个匹配周几且时刻晚于 now 的
                for (i in 0..7) {
                    val day = (now.clone() as Calendar).apply {
                        add(Calendar.DAY_OF_YEAR, i)
                    }
                    val c = at(day)
                    if (c.timeInMillis <= now.timeInMillis) continue
                    // Java: SUNDAY=1..SATURDAY=7 → 本应用: 1=周一..7=周日
                    val raw = c.get(Calendar.DAY_OF_WEEK)
                    val wd = if (raw == Calendar.SUNDAY) 7 else raw - 1
                    if (wd in item.days) return c.timeInMillis
                }
                return null
            }
            else -> { // daily
                val c = at(now)
                // 触发时刻通常 == now（提前几百毫秒时会重排到当天一次，
                // 再触发时已过时刻→顺延明天；60 秒去重防双开）
                if (c.timeInMillis <= now.timeInMillis) c.add(Calendar.DAY_OF_YEAR, 1)
                return c.timeInMillis
            }
        }
    }

    /** 原生闹钟触发后的自愈重排：保持同一 id，续到下一次触发时刻 */
    fun rescheduleNext(context: Context, item: Item) {
        val am = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val nextAt = nextAtMillis(Calendar.getInstance(), item)
        if (nextAt == null) {
            // 一次性任务已过期：从持久化集合移除，避免残留空 id
            val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            val old = prefs.getStringSet(KEY_IDS, emptySet())
                ?.mapNotNull { it.toIntOrNull() }?.toMutableSet() ?: mutableSetOf()
            if (old.remove(item.id)) {
                prefs.edit()
                    .putStringSet(KEY_IDS, old.map { it.toString() }.toSet())
                    .apply()
            }
            Log.d("SP-Alarm", "native reschedule: #${item.id} expired (one-shot), removed")
            return
        }
        scheduleInternal(context, am, item.copy(at = nextAt))
        Log.d("SP-Alarm", "native rescheduled #${item.id} -> $nextAt (${item.freq}/${item.time})")
    }
}
