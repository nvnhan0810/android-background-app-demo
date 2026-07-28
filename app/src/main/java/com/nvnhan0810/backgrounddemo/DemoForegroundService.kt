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
                    stopListen(removeNotification = true)
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

        // Restart poll thread nếu đang chạy (token mới / bấm Bật nghe lại).
        if (pollThreadAlive.get()) {
            LearningLog.i(TAG, "Restarting poll thread")
            pollThreadAlive.set(false)
            pollThread?.interrupt()
            try {
                pollThread?.join(1_500)
            } catch (_: InterruptedException) {
                // ignore
            }
        }
        pollThreadAlive.set(true)
        pollThread = Thread({ pollLoop() }, "tg-long-poll").also { it.start() }
        LearningLog.i(TAG, "Long-poll thread started")
    }

    private fun stopListen(removeNotification: Boolean) {
        pollThreadAlive.set(false)
        pollThread?.interrupt()
        pollThread = null
        releaseWakeLock()
        if (removeNotification) {
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
        }
        LearningLog.i(TAG, "stopListen removeNotification=$removeNotification")
    }

    private fun pollLoop() {
        val token = TelegramConfig.getBotToken(this)
        if (token.isBlank()) {
            LearningLog.e(TAG, "Bot token trống — dừng poll")
            failAndStop("Chưa có bot token")
            return
        }
        if (!TelegramConfig.looksLikeBotToken(token)) {
            LearningLog.e(TAG, "Token format lạ len=${token.length} — thường dính space khi paste")
            failAndStop("Token sai format — paste lại từ BotFather")
            return
        }

        try {
            LearningLog.i(TAG, "getMe kiểm tra token…")
            updateNotification("Đang kiểm tra token…")
            val me = TelegramApi.getMe(token)
            updateNotification("OK @${me.username} — polling…")
            sendBroadcast(
                Intent(ACTION_STATUS).setPackage(packageName)
                    .putExtra(EXTRA_STATUS, "Token OK @${me.username} — đang nghe")
            )
        } catch (t: Throwable) {
            LearningLog.e(TAG, "getMe failed — không poll", t)
            val msg = when {
                t is TelegramApi.HttpException && t.httpCode == 401 ->
                    "HTTP 401: token sai/hết hạn. Lưu lại token rồi Bật nghe."
                else -> "Token lỗi: ${t.message?.take(80)}"
            }
            failAndStop(msg)
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
                        } catch (err: Throwable) {
                            LearningLog.e(TAG, "Auto-reply failed", err)
                        }
                        sendBroadcast(Intent(ACTION_LEDGER_CHANGED).setPackage(packageName))
                    }
                }
                TelegramConfig.setUpdateOffset(this, maxId)
                updateNotification("Đã xử lý ${updates.size} update(s), nextOffset=$maxId")
            } catch (t: Throwable) {
                if (t is TelegramApi.HttpException && t.httpCode == 401) {
                    LearningLog.e(TAG, "Poll 401 — dừng hẳn", t)
                    failAndStop("HTTP 401 khi poll — kiểm tra lại token")
                    return
                }
                consecutiveErrors++
                LearningLog.e(TAG, "Poll error #$consecutiveErrors", t)
                updateNotification("Lỗi poll: ${t.message?.take(40)}")
                sendBroadcast(
                    Intent(ACTION_STATUS).setPackage(packageName)
                        .putExtra(EXTRA_STATUS, "Lỗi poll: ${t.message?.take(60)}")
                )
                try {
                    Thread.sleep((2_000L * consecutiveErrors).coerceAtMost(30_000L))
                } catch (_: InterruptedException) {
                    break
                }
            }
        }
        LearningLog.i(TAG, "pollLoop ended")
    }

    private fun failAndStop(message: String) {
        LearningLog.e(TAG, "failAndStop: $message")
        updateNotification(message)
        sendBroadcast(
            Intent(ACTION_STATUS).setPackage(packageName).putExtra(EXTRA_STATUS, message)
        )
        KeepAliveStore.setServiceEnabled(this, false)
        TelegramConfig.setListenEnabled(this, false)
        pollThreadAlive.set(false)
        releaseWakeLock()
        // Giữ notification lỗi vài giây để user đọc; không remove ngay.
        stopSelf()
    }

    private fun acquireWakeLock() {
        try {
            if (wakeLock?.isHeld == true) {
                // Re-acquire với timeout mới
                wakeLock?.release()
            }
            val pm = getSystemService(PowerManager::class.java)
            wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "bgdemo:tg-poll").apply {
                setReferenceCounted(false)
                acquire(60 * 60 * 1000L)
            }
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
            .setStyle(NotificationCompat.BigTextStyle().bigText(content))
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
        const val ACTION_STATUS = "com.nvnhan0810.backgrounddemo.action.STATUS"
        const val EXTRA_STATUS = "status"

        private const val TAG = "TgFgService"
        private const val CHANNEL_ID = "demo_foreground_channel"
        private const val NOTIFICATION_ID = 1001
    }
}
