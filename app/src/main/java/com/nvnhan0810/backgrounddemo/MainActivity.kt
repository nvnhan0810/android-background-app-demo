package com.nvnhan0810.backgrounddemo

import android.Manifest
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
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
import com.nvnhan0810.backgrounddemo.databinding.ActivityMainBinding
import com.nvnhan0810.backgrounddemo.db.DatabaseProvider
import com.nvnhan0810.backgrounddemo.ledger.LedgerBackup
import com.nvnhan0810.backgrounddemo.ledger.LedgerQueue
import com.nvnhan0810.backgrounddemo.ledger.MessageParser
import com.nvnhan0810.backgrounddemo.telegram.TelegramApi
import com.nvnhan0810.backgrounddemo.telegram.TelegramConfig
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding

    private val ledgerChangedReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                DemoForegroundService.ACTION_LEDGER_CHANGED -> {
                    refreshTotals()
                    refreshJournal()
                }
                DemoForegroundService.ACTION_STATUS -> {
                    val status = intent.getStringExtra(DemoForegroundService.EXTRA_STATUS)
                    if (!status.isNullOrBlank()) {
                        binding.txtStatus.text = status
                    }
                }
            }
        }
    }

    private val notificationPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            LearningLog.i(TAG, "POST_NOTIFICATIONS result granted=$granted")
            if (granted) {
                startListenService()
            } else {
                LearningLog.w(TAG, "User denied notification permission")
                Toast.makeText(this, R.string.notification_permission_denied, Toast.LENGTH_SHORT)
                    .show()
            }
        }

    private val exportLauncher =
        registerForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
            if (uri == null) return@registerForActivityResult
            val ok = LedgerBackup.exportToUri(this, uri)
            Toast.makeText(
                this,
                if (ok) R.string.export_ok else R.string.export_fail,
                Toast.LENGTH_SHORT
            ).show()
            binding.txtStatus.setText(if (ok) R.string.export_ok else R.string.export_fail)
        }

    private val importLauncher =
        registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            if (uri == null) return@registerForActivityResult
            val ok = LedgerBackup.importFromUri(this, uri)
            Toast.makeText(
                this,
                if (ok) R.string.import_ok else R.string.import_fail,
                Toast.LENGTH_SHORT
            ).show()
            binding.txtStatus.setText(if (ok) R.string.import_ok else R.string.import_fail)
            if (ok) {
                refreshTotals()
                refreshJournal()
                syncRatioToggle()
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.rootScroll)
        applySafeAreaInsets()

        LearningLog.i(TAG, "onCreate — ledger page (log ở trang riêng)")

        binding.edtToken.setText(TelegramConfig.getBotToken(this))
        syncRatioToggle()
        refreshTotals()
        refreshJournal()
        refreshKeepAliveStatus()

        binding.btnOpenLog.setOnClickListener {
            startActivity(Intent(this, LearningLogActivity::class.java))
        }

        binding.btnSaveToken.setOnClickListener {
            saveTokenFromUi(validateRemote = true)
        }

        binding.btnStartService.setOnClickListener {
            LearningLog.d(TAG, "Click: Start Telegram listen")
            // Luôn lưu lại ô token trước khi bật (tránh quên bấm Lưu).
            if (!saveTokenFromUi(validateRemote = false)) return@setOnClickListener
            ensureNotificationPermissionThenStart()
        }
        binding.btnStopService.setOnClickListener {
            LearningLog.d(TAG, "Click: Stop Telegram listen")
            stopListenService()
        }
        binding.btnBatteryOpt.setOnClickListener {
            requestIgnoreBatteryOptimizations()
        }
        binding.btnExport.setOnClickListener {
            exportLauncher.launch("tg-ledger-backup-${System.currentTimeMillis()}.json")
        }
        binding.btnImport.setOnClickListener {
            importLauncher.launch(arrayOf("application/json", "*/*"))
        }

        binding.toggleRatio.addOnButtonCheckedListener { _, checkedId, isChecked ->
            if (!isChecked) return@addOnButtonCheckedListener
            val ratio = when (checkedId) {
                R.id.btnRatio12 -> 1.2
                R.id.btnRatio15 -> 1.5
                else -> 1.0
            }
            TelegramConfig.setPriceRatio(this, ratio)
            refreshTotals()
        }

        binding.btnSettle0.setOnClickListener { settle(0) }
        binding.btnSettle1.setOnClickListener { settle(1) }
        binding.btnSettle2.setOnClickListener { settle(2) }
        binding.btnSettle3.setOnClickListener { settle(3) }
        binding.btnSettle4.setOnClickListener { settle(4) }
    }

    /**
     * @return false nếu token trống / format sai
     */
    private fun saveTokenFromUi(validateRemote: Boolean): Boolean {
        val raw = binding.edtToken.text?.toString().orEmpty()
        val clean = TelegramConfig.sanitizeToken(raw)
        binding.edtToken.setText(clean)
        if (clean.isBlank()) {
            Toast.makeText(this, R.string.token_missing, Toast.LENGTH_SHORT).show()
            binding.txtStatus.setText(R.string.token_missing)
            return false
        }
        if (!TelegramConfig.looksLikeBotToken(clean)) {
            val msg = getString(R.string.token_bad_format, clean.length)
            Toast.makeText(this, msg, Toast.LENGTH_LONG).show()
            binding.txtStatus.text = msg
            LearningLog.w(TAG, msg)
            TelegramConfig.setBotToken(this, clean) // vẫn lưu để user sửa tiếp
            return false
        }
        TelegramConfig.setBotToken(this, clean)
        LearningLog.i(TAG, "Token saved locally len=${clean.length}")

        if (!validateRemote) {
            binding.txtStatus.setText(R.string.token_saved)
            return true
        }

        binding.txtStatus.setText(R.string.token_checking)
        Thread {
            try {
                val me = TelegramApi.getMe(clean)
                runOnUiThread {
                    val ok = getString(R.string.token_ok, me.username.ifBlank { me.firstName })
                    binding.txtStatus.text = ok
                    Toast.makeText(this, ok, Toast.LENGTH_SHORT).show()
                }
            } catch (t: Throwable) {
                LearningLog.e(TAG, "getMe while save failed", t)
                runOnUiThread {
                    val err = when {
                        t is TelegramApi.HttpException && t.httpCode == 401 ->
                            getString(R.string.token_unauthorized)
                        else -> getString(R.string.token_check_fail, t.message?.take(80) ?: "?")
                    }
                    binding.txtStatus.text = err
                    Toast.makeText(this, err, Toast.LENGTH_LONG).show()
                }
            }
        }.start()
        return true
    }

    override fun onStart() {
        super.onStart()
        val filter = IntentFilter().apply {
            addAction(DemoForegroundService.ACTION_LEDGER_CHANGED)
            addAction(DemoForegroundService.ACTION_STATUS)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(ledgerChangedReceiver, filter, RECEIVER_NOT_EXPORTED)
        } else {
            registerReceiver(ledgerChangedReceiver, filter)
        }
        refreshTotals()
        refreshJournal()
    }

    override fun onStop() {
        try {
            unregisterReceiver(ledgerChangedReceiver)
        } catch (_: Throwable) {
            // ignore
        }
        super.onStop()
    }

    private var loggedSafeAreaOnce = false

    private fun applySafeAreaInsets() {
        val contentPadPx = TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_DIP,
            16f,
            resources.displayMetrics
        ).toInt()

        ViewCompat.setOnApplyWindowInsetsListener(binding.rootScroll) { view, windowInsets ->
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
                    "Safe area L=${bars.left} T=${bars.top} R=${bars.right} B=${bars.bottom}"
                )
            }
            windowInsets
        }
        ViewCompat.requestApplyInsets(binding.rootScroll)
    }

    private fun settle(positions: Int) {
        LearningLog.d(TAG, "Click settle positions=$positions")
        Thread {
            val msg = LedgerQueue.settleAndWait(positions)
            runOnUiThread {
                binding.txtStatus.text = msg
                Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
                refreshTotals()
            }
        }.start()
    }

    private fun syncRatioToggle() {
        val ratio = TelegramConfig.getPriceRatio(this)
        val id = when {
            kotlin.math.abs(ratio - 1.2) < 0.001 -> R.id.btnRatio12
            kotlin.math.abs(ratio - 1.5) < 0.001 -> R.id.btnRatio15
            else -> R.id.btnRatio10
        }
        binding.toggleRatio.check(id)
    }

    private fun refreshTotals() {
        try {
            val db = DatabaseProvider.get(this)
            val totals = db.ledgerEntryDao().totalsByCode()
            val customers = db.ledgerEntryDao().balancesByCustomer()
            val net = db.ledgerEntryDao().netQty()
            val ratio = TelegramConfig.getPriceRatio(this)
            val estimate = net * ratio

            binding.txtTotals.text = if (totals.isEmpty()) {
                getString(R.string.totals_empty)
            } else {
                totals.joinToString("\n") {
                    "${it.code}: ${MessageParser.formatQty(it.totalQty)}"
                }
            }

            binding.txtBalanceDiff.text = getString(
                R.string.balance_diff,
                MessageParser.formatQty(net),
                MessageParser.formatQty(estimate),
                ratio.toString()
            )

            val selected = TelegramConfig.getSelectedCustomerName(this).ifBlank { "—" }
            val selectedId = TelegramConfig.getSelectedChatId(this)
            binding.txtSelectedCustomer.text =
                getString(R.string.section_settle) + "\n" +
                    getString(R.string.selected_customer, "$selected ($selectedId)")

            binding.txtCustomers.text = if (customers.isEmpty()) {
                ""
            } else {
                customers.joinToString("\n") {
                    "• ${it.customerName}: ${MessageParser.formatQty(it.balanceQty)} " +
                        "(≈ ${MessageParser.formatQty(it.balanceQty * ratio)})"
                }
            }
        } catch (t: Throwable) {
            LearningLog.e(TAG, "refreshTotals failed", t)
        }
    }

    private fun refreshJournal() {
        try {
            val rows = DatabaseProvider.get(this).inboundMessageDao().recent(40)
            val fmt = SimpleDateFormat("HH:mm:ss", Locale.getDefault())
            binding.txtJournal.text = if (rows.isEmpty()) {
                getString(R.string.journal_empty)
            } else {
                rows.joinToString("\n") { m ->
                    val t = fmt.format(Date(m.updatedAtEpochMs))
                    val edit = if (m.editCount > 0) " edit×${m.editCount}" else ""
                    "[$t]$edit ${m.customerName}: ${m.rawText.replace("\n", " | ")}"
                }
            }
        } catch (t: Throwable) {
            LearningLog.e(TAG, "refreshJournal failed", t)
        }
    }

    private fun ensureNotificationPermissionThenStart() {
        try {
            if (TelegramConfig.getBotToken(this).isBlank()) {
                Toast.makeText(this, R.string.token_missing, Toast.LENGTH_SHORT).show()
                binding.txtStatus.setText(R.string.token_missing)
                return
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                val granted = ContextCompat.checkSelfPermission(
                    this,
                    Manifest.permission.POST_NOTIFICATIONS
                ) == PackageManager.PERMISSION_GRANTED
                LearningLog.i(TAG, "POST_NOTIFICATIONS granted=$granted")
                if (!granted) {
                    notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                    return
                }
            }
            startListenService()
        } catch (t: Throwable) {
            LearningLog.e(TAG, "ensureNotificationPermissionThenStart failed", t)
        }
    }

    private fun startListenService() {
        try {
            KeepAliveStore.setServiceEnabled(this, true)
            TelegramConfig.setListenEnabled(this, true)
            val intent = Intent(this, DemoForegroundService::class.java).apply {
                action = DemoForegroundService.ACTION_START
            }
            ContextCompat.startForegroundService(this, intent)
            binding.txtStatus.setText(R.string.status_service_running)
            LearningLog.i(TAG, "startForegroundService Telegram listen ON")
        } catch (t: Throwable) {
            LearningLog.e(TAG, "startListenService failed", t)
        }
    }

    private fun stopListenService() {
        try {
            KeepAliveStore.setServiceEnabled(this, false)
            TelegramConfig.setListenEnabled(this, false)
            val intent = Intent(this, DemoForegroundService::class.java).apply {
                action = DemoForegroundService.ACTION_STOP
            }
            startService(intent)
            binding.txtStatus.setText(R.string.status_service_stopped)
            LearningLog.i(TAG, "Telegram listen OFF")
        } catch (t: Throwable) {
            LearningLog.e(TAG, "stopListenService failed", t)
        }
    }

    private fun requestIgnoreBatteryOptimizations() {
        try {
            val pm = getSystemService(PowerManager::class.java)
            if (pm.isIgnoringBatteryOptimizations(packageName)) {
                binding.txtStatus.setText(R.string.status_battery_unrestricted)
                return
            }
            binding.txtStatus.setText(R.string.status_battery_restricted)
            startActivity(
                Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                    data = Uri.parse("package:$packageName")
                }
            )
        } catch (t: Throwable) {
            LearningLog.e(TAG, "requestIgnoreBatteryOptimizations failed", t)
        }
    }

    private fun refreshKeepAliveStatus() {
        val serviceOn = KeepAliveStore.isServiceEnabled(this)
        val listenOn = TelegramConfig.isListenEnabled(this)
        binding.txtStatus.text = getString(
            R.string.status_keepalive,
            if (serviceOn) "ON" else "OFF",
            if (listenOn) "ON" else "OFF"
        )
    }

    companion object {
        private const val TAG = "MainActivity"
    }
}
