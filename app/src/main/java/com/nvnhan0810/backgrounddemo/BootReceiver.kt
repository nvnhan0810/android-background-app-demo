package com.nvnhan0810.backgrounddemo

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * BroadcastReceiver = nghe sự kiện hệ thống (broadcast).
 * BOOT_COMPLETED: máy vừa boot xong → app có cơ hội chạy lại dù trước đó bị kill / tắt máy.
 *
 * Lưu ý: lúc máy TẮT hoàn toàn thì không process nào chạy. “Luôn work sau tắt máy”
 * = tự resume SAU KHI bật lại.
 */
class BootReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent?) {
        val action = intent?.action
        LearningLog.i(TAG, "onReceive action=$action")
        when (action) {
            Intent.ACTION_BOOT_COMPLETED,
            Intent.ACTION_LOCKED_BOOT_COMPLETED,
            Intent.ACTION_MY_PACKAGE_REPLACED,
            ACTION_QUICKBOOT_POWERON,
            ACTION_HTC_QUICKBOOT -> {
                // goAsync cho phép làm việc lâu hơn một chút trong receiver (learning-safe).
                val pending = goAsync()
                try {
                    KeepAliveRestorer.restore(context, reason = action ?: "unknown")
                } finally {
                    pending.finish()
                }
            }
            else -> LearningLog.w(TAG, "Ignored action=$action")
        }
    }

    companion object {
        private const val TAG = "BootReceiver"
        // Một số máy Xiaomi/HTC gửi quickboot thay vì BOOT_COMPLETED chuẩn
        private const val ACTION_QUICKBOOT_POWERON = "android.intent.action.QUICKBOOT_POWERON"
        private const val ACTION_HTC_QUICKBOOT = "com.htc.intent.action.QUICKBOOT_POWERON"
    }
}
