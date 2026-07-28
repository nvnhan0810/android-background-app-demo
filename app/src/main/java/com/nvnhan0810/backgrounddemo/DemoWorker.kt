package com.nvnhan0810.backgrounddemo

import android.content.Context
import androidx.work.Worker
import androidx.work.WorkerParameters

class DemoWorker(
    context: Context,
    params: WorkerParameters
) : Worker(context, params) {

    override fun doWork(): Result {
        return try {
            LearningLog.i(TAG, "doWork START id=$id runAttemptCount=$runAttemptCount")
            // Learning placeholder — work thật sẽ thêm sau
            LearningLog.i(TAG, "doWork SUCCESS at ${System.currentTimeMillis()}")
            Result.success()
        } catch (t: Throwable) {
            LearningLog.e(TAG, "doWork FAILED — returning retry", t)
            Result.retry()
        }
    }

    companion object {
        const val UNIQUE_WORK_NAME = "demo_periodic_work"
        private const val TAG = "DemoWorker"
    }
}
