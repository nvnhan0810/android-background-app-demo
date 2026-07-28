package com.nvnhan0810.backgrounddemo

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import com.nvnhan0810.backgrounddemo.ledger.LedgerProcessor
import com.nvnhan0810.backgrounddemo.ledger.LedgerQueue
import com.nvnhan0810.backgrounddemo.telegram.TelegramApi
import com.nvnhan0810.backgrounddemo.telegram.TelegramConfig
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Foreground Service long-poll Telegram getUpdates liên tục.
 * Giữ process sống khi tắt màn hình (kèm notification bắt buộc).
 */
class DemoForegroundService : Service() {

    private val pollThreadAlive = AtomicBoolean(false)

    @Volatile
    private var pollThread: Thread? = null

    private var wakeLock: PowerManager.WakeLock? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        LearningLog.i(TAG, "onCreate — Telegram listen service")
        LedgerQueue.start(LedgerProcessor(this))
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val action = intent?.action
        LearningLog.i(TAG, "onStartCommand action=$action flags=$flags startId=$startId")
        return try {
            when (action) {
                ACTION_STOP -> {
                    KeepAliveStore.setServiceEnabled(this, false)
                    TelegramConfig.setListenEnabled(this, false)
                    stopListen()
                    START_NOT_STICKY
                }
                else -> {
                    if (action == null) {
                        LearningLog.w(TAG, "Sticky restart — OS đưa service sống lại")
                    }
                    KeepAliveStore.setServiceEnabled(this, true)
                    TelegramConfig.setListenEnabled(this, true)
                    startListen()
                    START_STICKY
                }
            }
        } catch (t: Throwable) {
            LearningLog.e(TAG, "onStartCommand failed action=$action", t)
            START_NOT_STICKY
        }
    }

    private fun startListen() {
        createNotificationChannel()
        val notification = buildNotification(getString(R.string.notification_starting))
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ServiceCompat.startForeground(
                this,
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }

        acquireWakeLock()

        if (pollThreadAlive.get()) {
            LearningLog.i(TAG, "Poll thread already running")
            return
        }
        pollThreadAlive.set(true)
        pollThread = Thread({
            pollLoop()
        }, "tg-long-poll").also { it.start() }
        LearningLog.i(TAG, "Long-poll thread started")
    }

    private fun stopListen() {
        pollThreadAlive.set(false)
        pollThread?.interrupt()
        pollThread = null
        releaseWakeLock()
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
        LearningLog.i(TAG, "stopListen — stopForeground + stopSelf")
    }

    private fun pollLoop() {
        val token = TelegramConfig.getBotToken(this)
        if (token.isBlank()) {
            LearningLog.e(TAG, "Bot token trống — dừng poll")
            updateNotification("Chưa có bot token")
            pollThreadAlive.set(false)
            return
        }

        LearningLog.i(TAG, "deleteWebhook trước khi long-poll…")
        TelegramApi.deleteWebhook(token)

        var consecutiveErrors = 0
        while (pollThreadAlive.get()) {
            try {
                val offset = TelegramConfig.getUpdateOffset(this)
                updateNotification("Polling… offset=$offset")
                val updates = TelegramApi.getUpdates(token, offset, timeoutSec = 25)
                consecutiveErrors = 0
                // Gia hạn wake lock định kỳ (PARTIAL_WAKE_LOCK có timeout).
                acquireWakeLock()

                if (updates.isEmpty()) {
                    LearningLog.d(TAG, "getUpdates empty (timeout) offset=$offset")
                    continue
                }

                var maxId = offset
                for (u in updates) {
                    if (u.updateId >= maxId) maxId = u.updateId + 1
                    val isEdit = u.kind == TelegramApi.TgUpdate.Kind.EDITED_MESSAGE
                    LearningLog.i(
                        TAG,
                        "Update ${u.kind} chat=${u.chatId} msg=${u.messageId} text=${u.text.take(60)}"
                    )
                    LedgerQueue.enqueueInbound(
                        platform = LedgerProcessor.PLATFORM_TELEGRAM,
                        chatId = u.chatId.toString(),
                        messageId = u.messageId.toString(),
                        customerName = u.fromName,
                        rawText = u.text,
                        isEdit = isEdit
                    ) { reply ->
                        LearningLog.i(TAG, "Ledger reply: $reply")
                        try {
                            TelegramApi.sendMessage(token, u.chatId, reply)
                        } catch (t: Throwable) {
                            LearningLog.e(TAG, "Auto-reply failed", t)
                        }
                        // Broadcast để UI refresh (nếu đang mở)
                        sendBroadcast(Intent(ACTION_LEDGER_CHANGED).setPackage(packageName))
                    }
                }
                TelegramConfig.setUpdateOffset(this, maxId)
                updateNotification("Đã xử lý ${updates.size} update(s), nextOffset=$maxId")
            } catch (t: Throwable) {
                consecutiveErrors++
                LearningLog.e(TAG, "Poll error #$consecutiveErrors", t)
                updateNotification("Lỗi poll: ${t.message?.take(40)}")
                try {
                    Thread.sleep((2_000L * consecutiveErrors).coerceAtMost(30_000L))
                } catch (_: InterruptedException) {
                    break
                }
            }
        }
        LearningLog.i(TAG, "pollLoop ended")
    }

    private fun acquireWakeLock() {
        try {
            if (wakeLock?.isHeld == true) return
            val pm = getSystemService(PowerManager::class.java)
            wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "bgdemo:tg-poll").apply {
                setReferenceCounted(false)
                acquire(60 * 60 * 1000L) // 60 phút; poll loop sẽ refresh
            }
            LearningLog.d(TAG, "PARTIAL_WAKE_LOCK acquired")
        } catch (t: Throwable) {
            LearningLog.e(TAG, "WakeLock failed", t)
        }
    }

    private fun releaseWakeLock() {
        try {
            wakeLock?.let {
                if (it.isHeld) it.release()
            }
            wakeLock = null
        } catch (t: Throwable) {
            LearningLog.e(TAG, "WakeLock release failed", t)
        }
    }

    override fun onDestroy() {
        pollThreadAlive.set(false)
        pollThread?.interrupt()
        releaseWakeLock()
        LearningLog.i(TAG, "onDestroy")
        super.onDestroy()
    }

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            getString(R.string.notification_channel_name),
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = getString(R.string.notification_channel_description)
        }
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    private fun updateNotification(content: String) {
        try {
            getSystemService(NotificationManager::class.java)
                .notify(NOTIFICATION_ID, buildNotification(content))
        } catch (t: Throwable) {
            LearningLog.e(TAG, "updateNotification failed", t)
        }
    }

    private fun buildNotification(content: String): Notification {
        val launchIntent = Intent(this, MainActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            launchIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(getString(R.string.notification_title))
            .setContentText(content)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .build()
    }

    companion object {
        const val ACTION_START = "com.nvnhan0810.backgrounddemo.action.START"
        const val ACTION_STOP = "com.nvnhan0810.backgrounddemo.action.STOP"
        const val ACTION_LEDGER_CHANGED = "com.nvnhan0810.backgrounddemo.action.LEDGER_CHANGED"

        private const val TAG = "TgFgService"
        private const val CHANNEL_ID = "demo_foreground_channel"
        private const val NOTIFICATION_ID = 1001
    }
}
