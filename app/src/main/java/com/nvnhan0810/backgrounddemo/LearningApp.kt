package com.nvnhan0810.backgrounddemo

import android.app.Application

/**
 * Application = object sống suốt vòng đời process app.
 * Đăng ký UncaughtExceptionHandler để crash cũng hiện trên LearningLog (khi còn kịp).
 */
class LearningApp : Application() {

    override fun onCreate() {
        super.onCreate()
        LearningLog.i(TAG, "Application.onCreate — process started")

        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            LearningLog.e(
                TAG,
                "Uncaught exception on thread=${thread.name}",
                throwable
            )
            // Chờ một nhịp ngắn để listener kịp vẽ (learning only; không dùng production)
            try {
                Thread.sleep(300)
            } catch (_: InterruptedException) {
                // ignore
            }
            previous?.uncaughtException(thread, throwable)
        }
    }

    companion object {
        private const val TAG = "LearningApp"
    }
}
