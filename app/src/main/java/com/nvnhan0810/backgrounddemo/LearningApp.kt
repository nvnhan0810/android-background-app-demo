package com.nvnhan0810.backgrounddemo

import android.app.Application
import com.nvnhan0810.backgrounddemo.db.DatabaseProvider
import com.nvnhan0810.backgrounddemo.ledger.LedgerProcessor
import com.nvnhan0810.backgrounddemo.ledger.LedgerQueue

/**
 * Application = object sống suốt vòng đời process app.
 * Đăng ký UncaughtExceptionHandler để crash cũng hiện trên LearningLog (khi còn kịp).
 * Đồng thời mở SQLite (Room) local ngay khi app start.
 * Nếu user đã bật keep-alive → restore service/work khi process sống lại.
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

        openLocalDatabase()
        // Single-writer queue sẵn sàng cả khi UI tất toán lúc service chưa chạy.
        LedgerQueue.start(LedgerProcessor(this))
        KeepAliveRestorer.restore(this, reason = "LearningApp.onCreate")
    }

    private fun openLocalDatabase() {
        try {
            LearningLog.i(TAG, "Opening local SQLite (Room) — no remote DB")
            val info = DatabaseProvider.openAndSmokeTest(this)
            if (info.ok) {
                LearningLog.i(TAG, "Local DB ready: ${info.dbName}")
            } else {
                LearningLog.e(TAG, "Local DB open smoke-test returned not ok")
            }
        } catch (t: Throwable) {
            LearningLog.e(TAG, "Failed to open local SQLite", t)
        }
    }

    companion object {
        private const val TAG = "LearningApp"
    }
}
