package com.nvnhan0810.backgrounddemo

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.util.TypedValue
import android.widget.Toast
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import com.nvnhan0810.backgrounddemo.databinding.ActivityMainBinding
import com.nvnhan0810.backgrounddemo.db.DatabaseProvider
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
        // Edge-to-edge: app vẽ dưới status/nav bar; ta tự chừa safe area bằng WindowInsets.
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        applySafeAreaInsets()

        LearningLog.addListener(logListener)
        LearningLog.i(TAG, "onCreate — UI ready, savedInstanceState=${savedInstanceState != null}")
        refreshKeepAliveStatus()

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
        binding.btnTestDb.setOnClickListener {
            LearningLog.d(TAG, "Click: Test local SQLite")
            testLocalDatabase()
        }
        binding.btnBatteryOpt.setOnClickListener {
            LearningLog.d(TAG, "Click: Request ignore battery optimizations")
            requestIgnoreBatteryOptimizations()
        }
        binding.btnClearLog.setOnClickListener {
            LearningLog.clear()
            LearningLog.i(TAG, "Log cleared by user")
        }
    }

    private var loggedSafeAreaOnce = false

    /**
     * Safe area = vùng không bị status bar (trên) / navigation bar (dưới) che.
     * Cộng thêm content padding 16dp để UI không dính sát mép.
     */
    private fun applySafeAreaInsets() {
        val contentPadPx = TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_DIP,
            16f,
            resources.displayMetrics
        ).toInt()

        ViewCompat.setOnApplyWindowInsetsListener(binding.root) { view, windowInsets ->
            val bars = windowInsets.getInsets(WindowInsetsCompat.Type.systemBars())
            view.updatePadding(
                left = bars.left + contentPadPx,
                top = bars.top + contentPadPx,
                right = bars.right + contentPadPx,
                bottom = bars.bottom + contentPadPx
            )
            if (!loggedSafeAreaOnce) {
                loggedSafeAreaOnce = true
                LearningLog.i(
                    TAG,
                    "Safe area applied L=${bars.left} T=${bars.top} R=${bars.right} B=${bars.bottom} +pad=${contentPadPx}px"
                )
            }
            windowInsets
        }
        // Yêu cầu hệ thống gửi insets ngay (một số máy không fire listener lần đầu nếu thiếu).
        ViewCompat.requestApplyInsets(binding.root)
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
            KeepAliveStore.setServiceEnabled(this, true)
            val intent = Intent(this, DemoForegroundService::class.java).apply {
                action = DemoForegroundService.ACTION_START
            }
            ContextCompat.startForegroundService(this, intent)
            binding.txtStatus.setText(R.string.status_service_running)
            LearningLog.i(TAG, "startForegroundService(ACTION_START) + keep-alive flag ON")
            refreshKeepAliveStatus()
        } catch (t: Throwable) {
            LearningLog.e(TAG, "startDemoService failed", t)
        }
    }

    private fun stopDemoService() {
        try {
            KeepAliveStore.setServiceEnabled(this, false)
            val intent = Intent(this, DemoForegroundService::class.java).apply {
                action = DemoForegroundService.ACTION_STOP
            }
            startService(intent)
            binding.txtStatus.setText(R.string.status_service_stopped)
            LearningLog.i(TAG, "startService(ACTION_STOP) + keep-alive flag OFF")
            refreshKeepAliveStatus()
        } catch (t: Throwable) {
            LearningLog.e(TAG, "stopDemoService failed", t)
        }
    }

    private fun schedulePeriodicWork() {
        try {
            KeepAliveStore.setWorkEnabled(this, true)
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
                "WorkManager enqueueUniquePeriodicWork name=${DemoWorker.UNIQUE_WORK_NAME} every=15min + flag ON"
            )
            refreshKeepAliveStatus()
        } catch (t: Throwable) {
            LearningLog.e(TAG, "schedulePeriodicWork failed", t)
        }
    }

    private fun cancelPeriodicWork() {
        try {
            KeepAliveStore.setWorkEnabled(this, false)
            WorkManager.getInstance(this).cancelUniqueWork(DemoWorker.UNIQUE_WORK_NAME)
            binding.txtStatus.setText(R.string.status_work_cancelled)
            LearningLog.i(TAG, "WorkManager cancelUniqueWork + flag OFF")
            refreshKeepAliveStatus()
        } catch (t: Throwable) {
            LearningLog.e(TAG, "cancelPeriodicWork failed", t)
        }
    }

    private fun requestIgnoreBatteryOptimizations() {
        try {
            val pm = getSystemService(PowerManager::class.java)
            if (pm.isIgnoringBatteryOptimizations(packageName)) {
                binding.txtStatus.setText(R.string.status_battery_unrestricted)
                LearningLog.i(TAG, "Battery optimizations already ignored for $packageName")
                return
            }
            binding.txtStatus.setText(R.string.status_battery_restricted)
            val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                data = Uri.parse("package:$packageName")
            }
            startActivity(intent)
            LearningLog.i(TAG, "Launched ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS")
        } catch (t: Throwable) {
            LearningLog.e(TAG, "requestIgnoreBatteryOptimizations failed", t)
        }
    }

    private fun refreshKeepAliveStatus() {
        val serviceOn = KeepAliveStore.isServiceEnabled(this)
        val workOn = KeepAliveStore.isWorkEnabled(this)
        LearningLog.i(TAG, "KeepAlive flags service=$serviceOn work=$workOn")
        // Không ghi đè status nếu user vừa thấy message khác; chỉ log + subtitle-style khi idle
        if (binding.txtStatus.text == getString(R.string.status_idle)) {
            binding.txtStatus.text = getString(
                R.string.status_keepalive,
                if (serviceOn) "ON" else "OFF",
                if (workOn) "ON" else "OFF"
            )
        }
    }

    private fun testLocalDatabase() {
        try {
            val info = DatabaseProvider.openAndSmokeTest(this)
            if (info.ok) {
                binding.txtStatus.text = getString(
                    R.string.status_db_ok,
                    info.dbName,
                    info.metaCount
                )
            } else {
                binding.txtStatus.setText(R.string.status_db_fail)
            }
        } catch (t: Throwable) {
            binding.txtStatus.setText(R.string.status_db_fail)
            LearningLog.e(TAG, "testLocalDatabase failed", t)
        }
    }

    companion object {
        private const val TAG = "MainActivity"
    }
}
