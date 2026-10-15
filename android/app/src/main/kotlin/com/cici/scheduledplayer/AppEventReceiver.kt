package com.cici.scheduledplayer

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log

/**
 * 系统事件自愈接收器：把「闹钟被系统清空」的几个必然场景一次性兜住。
 *
 * 背景（"用一段时间就不再定时播放、手动编辑保存才恢复"的根因）：
 * AlarmManager 里注册的闹钟在下列情况会被系统全部清空——
 * 1. 手机重启：闹钟不跨重启存活；
 * 2. App 覆盖安装/升级：安装新版本时闹钟被清；
 * 3. 应用被强制停止（MIUI 省电策略、一键清理、系统休眠回收）；
 * 4. 系统时间/时区被修改：原闹钟基于旧时刻，时刻全错。
 * 此前只有"用户主动打开 App"这一条恢复路径，所以一旦长期不打开，
 * 定时任务就静默失效；用户手动编辑保存（等于打开 App + 重排）才恢复。
 *
 * 本接收器在这些系统事件后直接调 [UrlAlarmScheduler.restoreAll] 原地重建，
 * 全程不需要拉起 Flutter、不需要用户操作。
 */
class AppEventReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action ?: return
        Log.d("SP-Alarm", "AppEventReceiver: $action")

        when (action) {
            Intent.ACTION_BOOT_COMPLETED,
            Intent.ACTION_MY_PACKAGE_REPLACED,
            Intent.ACTION_TIME_CHANGED,
            Intent.ACTION_TIMEZONE_CHANGED,
            ACTION_EXACT_ALARM_PERMISSION_CHANGED -> {
                try {
                    val n = UrlAlarmScheduler.restoreAll(context)
                    Log.d("SP-Alarm", "restoreAll after $action -> $n 个闹钟已重建")
                } catch (e: Exception) {
                    Log.e("SP-Alarm", "restoreAll failed after $action: $e")
                }
                try {
                    // 顺带确保心跳自检任务在运行（重启/升级后 JobScheduler 任务可能已丢）
                    SelfHealJob.schedule(context)
                } catch (e: Exception) {
                    Log.e("SP-Alarm", "SelfHealJob.schedule failed: $e")
                }
            }
            else -> {}
        }
    }

    companion object {
        /** Android 12+：精确闹钟权限状态变化（用户授予后需重排，否则闹钟加不进去） */
        const val ACTION_EXACT_ALARM_PERMISSION_CHANGED =
            "android.app.action.SCHEDULE_EXACT_ALARM_PERMISSION_STATE_CHANGED"
    }
}
