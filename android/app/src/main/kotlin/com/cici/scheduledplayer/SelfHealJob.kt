package com.cici.scheduledplayer

import android.app.job.JobInfo
import android.app.job.JobParameters
import android.app.job.JobScheduler
import android.app.job.JobService
import android.content.ComponentName
import android.content.Context
import android.util.Log

/**
 * 心跳自检任务：周期性确认"闹钟还在不在"，丢失就补建。
 *
 * 为什么需要它：上面两类失效（重启/升级/时间变更）有系统广播可以捕获，
 * 但 ROM（尤其 MIUI）还会在没有任何广播的情况下悄悄回收后台应用的闹钟
 * （省电策略、进程高度回收后清理 cached alarm）。这种"暗失效"只能靠
 * 定期自检兜住——只要 App 没有被强制停止，JobScheduler 就能按时醒来。
 *
 * 用原生 JobScheduler 而不是 Flutter 侧方案：本任务不需要 Flutter 引擎，
 * 唤醒成本极低、不受后台 isolate 启动限制。
 * setPersisted(true) 让任务本身在手机重启后也被系统恢复。
 */
class SelfHealJob : JobService() {

    override fun onStartJob(params: JobParameters?): Boolean {
        try {
            val fixed = UrlAlarmScheduler.verifyAndFix(applicationContext)
            Log.d("SP-Alarm", "SelfHealJob 完成：补建 $fixed 个闹钟")
        } catch (e: Exception) {
            Log.e("SP-Alarm", "SelfHealJob 自检失败: $e")
        }
        jobFinished(params, false)
        return false   // 已在当前线程同步做完
    }

    override fun onStopJob(params: JobParameters?): Boolean = true  // 被中断则重排

    companion object {
        private const val JOB_ID = 20001
        private const val INTERVAL_MS = 4 * 60 * 60 * 1000L   // 4 小时一次

        fun schedule(context: Context) {
            try {
                val js = context.getSystemService(Context.JOB_SCHEDULER_SERVICE)
                    as JobScheduler
                val info = JobInfo.Builder(
                    JOB_ID, ComponentName(context, SelfHealJob::class.java)
                )
                    .setPeriodic(INTERVAL_MS)
                    .setPersisted(true)                                  // 重启后仍保留
                    .setRequiredNetworkType(JobInfo.NETWORK_TYPE_NONE)    // 不需要网络
                    .build()
                js.schedule(info)
                Log.d("SP-Alarm", "SelfHealJob 已排定（每 4 小时自检一次）")
            } catch (e: Exception) {
                Log.e("SP-Alarm", "SelfHealJob 排定失败: $e")
            }
        }
    }
}
