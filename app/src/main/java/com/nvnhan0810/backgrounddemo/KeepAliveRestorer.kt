package com.nvnhan0810.backgrounddemo

import android.content.Context
import android.content.Intent
import androidx.core.content.ContextCompat
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import java.util.concurrent.TimeUnit

/**
 * Đọc flag KeepAliveStore rồi khởi động lại Foreground Service / WorkManager.
 * Gọi từ: BootReceiver, LearningApp, sau khi user bật keep-alive.
 */
object KeepAliveRestorer {

    fun restore(context: Context, reason: String) {
        val app = context.applicationContext
        LearningLog.i(
            TAG,
            "Restore requested reason=$reason service=${KeepAliveStore.isServiceEnabled(app)} work=${KeepAliveStore.isWorkEnabled(app)}"
        )
        try {
            if (KeepAliveStore.isWorkEnabled(app)) {
                restoreWork(app)
            }
            if (KeepAliveStore.isServiceEnabled(app)) {
                restoreService(app)
            }
        } catch (t: Throwable) {
            LearningLog.e(TAG, "Restore failed reason=$reason", t)
        }
    }

    fun restoreWork(context: Context) {
        val request = PeriodicWorkRequestBuilder<DemoWorker>(15, TimeUnit.MINUTES).build()
        WorkManager.getInstance(context).enqueueUniquePeriodicWork(
            DemoWorker.UNIQUE_WORK_NAME,
            ExistingPeriodicWorkPolicy.KEEP,
            request
        )
        LearningLog.i(TAG, "WorkManager restored (KEEP) name=${DemoWorker.UNIQUE_WORK_NAME}")
    }

    fun restoreService(context: Context) {
        val intent = Intent(context, DemoForegroundService::class.java).apply {
            action = DemoForegroundService.ACTION_START
        }
        ContextCompat.startForegroundService(context, intent)
        LearningLog.i(TAG, "Foreground service restore startForegroundService(ACTION_START)")
    }

    private const val TAG = "KeepAliveRestorer"
}
