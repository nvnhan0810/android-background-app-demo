package com.nvnhan0810.backgrounddemo

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class DemoForegroundService : Service() {

    private val handler = Handler(Looper.getMainLooper())
    private var tickCount = 0

    private val ticker = object : Runnable {
        override fun run() {
            try {
                tickCount += 1
                val time = SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date())
                val content = getString(R.string.notification_content, tickCount, time)
                val notification = buildNotification(content)
                val manager = getSystemService(NotificationManager::class.java)
                manager.notify(NOTIFICATION_ID, notification)
                LearningLog.d(TAG, "tick #$tickCount at $time — notification updated")
                handler.postDelayed(this, TICK_INTERVAL_MS)
            } catch (t: Throwable) {
                LearningLog.e(TAG, "ticker failed at tick=$tickCount", t)
            }
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        LearningLog.i(TAG, "onCreate — service instance created")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val action = intent?.action
        LearningLog.i(TAG, "onStartCommand action=$action flags=$flags startId=$startId")
        return try {
            when (action) {
                ACTION_STOP -> {
                    stopDemo()
                    START_NOT_STICKY
                }
                else -> {
                    startDemo()
                    START_STICKY
                }
            }
        } catch (t: Throwable) {
            LearningLog.e(TAG, "onStartCommand failed action=$action", t)
            START_NOT_STICKY
        }
    }

    private fun startDemo() {
        createNotificationChannel()
        val notification = buildNotification(getString(R.string.notification_starting))

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ServiceCompat.startForeground(
                this,
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
            )
            LearningLog.i(TAG, "startForeground type=dataSync (API ${Build.VERSION.SDK_INT})")
        } else {
            startForeground(NOTIFICATION_ID, notification)
            LearningLog.i(TAG, "startForeground legacy (API ${Build.VERSION.SDK_INT})")
        }

        handler.removeCallbacks(ticker)
        tickCount = 0
        handler.post(ticker)
        LearningLog.i(TAG, "ticker scheduled every ${TICK_INTERVAL_MS}ms")
    }

    private fun stopDemo() {
        handler.removeCallbacks(ticker)
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
        LearningLog.i(TAG, "stopDemo — stopForeground + stopSelf")
    }

    override fun onDestroy() {
        handler.removeCallbacks(ticker)
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
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(channel)
        LearningLog.d(TAG, "NotificationChannel ensured id=$CHANNEL_ID")
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

        private const val TAG = "DemoFgService"
        private const val CHANNEL_ID = "demo_foreground_channel"
        private const val NOTIFICATION_ID = 1001
        private const val TICK_INTERVAL_MS = 5_000L
    }
}
