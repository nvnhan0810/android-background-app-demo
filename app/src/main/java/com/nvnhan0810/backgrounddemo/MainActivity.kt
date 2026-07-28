package com.nvnhan0810.backgrounddemo

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import com.nvnhan0810.backgrounddemo.databinding.ActivityMainBinding
import java.util.concurrent.TimeUnit

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding

    private val logListener: (List<LearningLog.Entry>) -> Unit = { entries ->
        binding.txtLearningLog.text = if (entries.isEmpty()) {
            getString(R.string.learning_log_empty)
        } else {
            entries.joinToString("\n") { it.toDisplayLine() }
        }
        binding.scrollLearningLog.post {
            binding.scrollLearningLog.fullScroll(android.view.View.FOCUS_DOWN)
        }
    }

    private val notificationPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            LearningLog.i(TAG, "POST_NOTIFICATIONS result granted=$granted")
            if (granted) {
                startDemoService()
            } else {
                LearningLog.w(TAG, "User denied notification permission — service not started")
                Toast.makeText(this, R.string.notification_permission_denied, Toast.LENGTH_SHORT)
                    .show()
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        LearningLog.addListener(logListener)
        LearningLog.i(TAG, "onCreate — UI ready, savedInstanceState=${savedInstanceState != null}")

        binding.btnStartService.setOnClickListener {
            LearningLog.d(TAG, "Click: Start foreground service")
            ensureNotificationPermissionThenStart()
        }
        binding.btnStopService.setOnClickListener {
            LearningLog.d(TAG, "Click: Stop foreground service")
            stopDemoService()
        }
        binding.btnScheduleWork.setOnClickListener {
            LearningLog.d(TAG, "Click: Schedule periodic work")
            schedulePeriodicWork()
        }
        binding.btnCancelWork.setOnClickListener {
            LearningLog.d(TAG, "Click: Cancel periodic work")
            cancelPeriodicWork()
        }
        binding.btnClearLog.setOnClickListener {
            LearningLog.clear()
            LearningLog.i(TAG, "Log cleared by user")
        }
    }

    override fun onDestroy() {
        LearningLog.i(TAG, "onDestroy — remove log listener")
        LearningLog.removeListener(logListener)
        super.onDestroy()
    }

    private fun ensureNotificationPermissionThenStart() {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                val granted = ContextCompat.checkSelfPermission(
                    this,
                    Manifest.permission.POST_NOTIFICATIONS
                ) == PackageManager.PERMISSION_GRANTED

                LearningLog.i(
                    TAG,
                    "Android 13+: check POST_NOTIFICATIONS → granted=$granted (sdk=${Build.VERSION.SDK_INT})"
                )

                if (!granted) {
                    LearningLog.i(TAG, "Launching runtime permission dialog")
                    notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                    return
                }
            } else {
                LearningLog.i(
                    TAG,
                    "SDK ${Build.VERSION.SDK_INT} < 33 — no runtime POST_NOTIFICATIONS needed"
                )
            }
            startDemoService()
        } catch (t: Throwable) {
            LearningLog.e(TAG, "ensureNotificationPermissionThenStart failed", t)
        }
    }

    private fun startDemoService() {
        try {
            val intent = Intent(this, DemoForegroundService::class.java).apply {
                action = DemoForegroundService.ACTION_START
            }
            ContextCompat.startForegroundService(this, intent)
            binding.txtStatus.setText(R.string.status_service_running)
            LearningLog.i(TAG, "startForegroundService(ACTION_START) called")
        } catch (t: Throwable) {
            LearningLog.e(TAG, "startDemoService failed", t)
        }
    }

    private fun stopDemoService() {
        try {
            val intent = Intent(this, DemoForegroundService::class.java).apply {
                action = DemoForegroundService.ACTION_STOP
            }
            startService(intent)
            binding.txtStatus.setText(R.string.status_service_stopped)
            LearningLog.i(TAG, "startService(ACTION_STOP) called")
        } catch (t: Throwable) {
            LearningLog.e(TAG, "stopDemoService failed", t)
        }
    }

    private fun schedulePeriodicWork() {
        try {
            val request = PeriodicWorkRequestBuilder<DemoWorker>(15, TimeUnit.MINUTES)
                .build()

            WorkManager.getInstance(this).enqueueUniquePeriodicWork(
                DemoWorker.UNIQUE_WORK_NAME,
                ExistingPeriodicWorkPolicy.UPDATE,
                request
            )
            binding.txtStatus.setText(R.string.status_work_scheduled)
            LearningLog.i(
                TAG,
                "WorkManager enqueueUniquePeriodicWork name=${DemoWorker.UNIQUE_WORK_NAME} every=15min"
            )
        } catch (t: Throwable) {
            LearningLog.e(TAG, "schedulePeriodicWork failed", t)
        }
    }

    private fun cancelPeriodicWork() {
        try {
            WorkManager.getInstance(this).cancelUniqueWork(DemoWorker.UNIQUE_WORK_NAME)
            binding.txtStatus.setText(R.string.status_work_cancelled)
            LearningLog.i(TAG, "WorkManager cancelUniqueWork name=${DemoWorker.UNIQUE_WORK_NAME}")
        } catch (t: Throwable) {
            LearningLog.e(TAG, "cancelPeriodicWork failed", t)
        }
    }

    companion object {
        private const val TAG = "MainActivity"
    }
}
