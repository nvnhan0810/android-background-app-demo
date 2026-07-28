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
import android.view.View
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
import com.nvnhan0810.backgrounddemo.telegram.TelegramConfig
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private var journalExpanded = false
    private var learningLogExpanded = true

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

    private val ledgerChangedReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            refreshTotals()
            refreshJournal()
            // Accordion tự mở khi có tin mới, rồi tự đóng sau vài giây (tiết kiệm chỗ).
            applyJournalExpanded(true)
            binding.rootScroll.removeCallbacks(collapseJournalRunnable)
            binding.rootScroll.postDelayed(collapseJournalRunnable, 4_000L)
        }
    }

    private val collapseJournalRunnable = Runnable {
        applyJournalExpanded(false)
    }

    private val notificationPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            LearningLog.i(TAG, "POST_NOTIFICATIONS result granted=$granted")
            if (granted) {
                startListenService()
            } else {
                LearningLog.w(TAG, "User denied notification permission — service not started")
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

        LearningLog.addListener(logListener)
        LearningLog.i(TAG, "onCreate — Telegram ledger UI")

        binding.edtToken.setText(TelegramConfig.getBotToken(this))
        syncRatioToggle()
        applyJournalExpanded(false)
        applyLearningLogExpanded(true)
        refreshTotals()
        refreshJournal()
        refreshKeepAliveStatus()

        binding.btnSaveToken.setOnClickListener {
            val token = binding.edtToken.text?.toString().orEmpty()
            TelegramConfig.setBotToken(this, token)
            Toast.makeText(this, R.string.token_saved, Toast.LENGTH_SHORT).show()
            binding.txtStatus.setText(R.string.token_saved)
        }

        binding.btnStartService.setOnClickListener {
            LearningLog.d(TAG, "Click: Start Telegram listen")
            ensureNotificationPermissionThenStart()
        }
        binding.btnStopService.setOnClickListener {
            LearningLog.d(TAG, "Click: Stop Telegram listen")
            stopListenService()
        }
        binding.btnBatteryOpt.setOnClickListener {
            requestIgnoreBatteryOptimizations()
        }
        binding.btnClearLog.setOnClickListener {
            LearningLog.clear()
            LearningLog.i(TAG, "Log cleared by user")
        }
        binding.btnExport.setOnClickListener {
            val name = "tg-ledger-backup-${System.currentTimeMillis()}.json"
            exportLauncher.launch(name)
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

        binding.headerJournal.setOnClickListener {
            applyJournalExpanded(!journalExpanded)
        }
        binding.headerLearningLog.setOnClickListener {
            // Clear button is separate; header toggles panel
            applyLearningLogExpanded(!learningLogExpanded)
        }
    }

    override fun onStart() {
        super.onStart()
        val filter = IntentFilter(DemoForegroundService.ACTION_LEDGER_CHANGED)
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
                    "Safe area applied L=${bars.left} T=${bars.top} R=${bars.right} B=${bars.bottom}"
                )
            }
            windowInsets
        }
        ViewCompat.requestApplyInsets(binding.rootScroll)
    }

    override fun onDestroy() {
        binding.rootScroll.removeCallbacks(collapseJournalRunnable)
        LearningLog.i(TAG, "onDestroy — remove log listener")
        LearningLog.removeListener(logListener)
        super.onDestroy()
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

    private fun applyJournalExpanded(expanded: Boolean) {
        journalExpanded = expanded
        binding.panelJournal.visibility = if (expanded) View.VISIBLE else View.GONE
        binding.txtJournalTitle.setText(
            if (expanded) R.string.journal_title_expanded else R.string.journal_title_collapsed
        )
        binding.txtJournalChevron.text = if (expanded) "▾" else "▸"
        if (expanded) refreshJournal()
    }

    private fun applyLearningLogExpanded(expanded: Boolean) {
        learningLogExpanded = expanded
        binding.panelLearningLog.visibility = if (expanded) View.VISIBLE else View.GONE
        binding.txtLogTitle.setText(
            if (expanded) R.string.learning_log_title_expanded else R.string.learning_log_title_collapsed
        )
        binding.txtLogChevron.text = if (expanded) "▾" else "▸"
    }

    private fun ensureNotificationPermissionThenStart() {
        try {
            if (TelegramConfig.getBotToken(this).isBlank() &&
                binding.edtToken.text?.isNotBlank() == true
            ) {
                TelegramConfig.setBotToken(this, binding.edtToken.text.toString())
            }
            if (TelegramConfig.getBotToken(this).isBlank()) {
                Toast.makeText(this, R.string.token_missing, Toast.LENGTH_SHORT).show()
                binding.txtStatus.setText(R.string.token_missing)
                LearningLog.w(TAG, "Start blocked — missing bot token")
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
            refreshKeepAliveStatus()
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
            refreshKeepAliveStatus()
        } catch (t: Throwable) {
            LearningLog.e(TAG, "stopListenService failed", t)
        }
    }

    private fun requestIgnoreBatteryOptimizations() {
        try {
            val pm = getSystemService(PowerManager::class.java)
            if (pm.isIgnoringBatteryOptimizations(packageName)) {
                binding.txtStatus.setText(R.string.status_battery_unrestricted)
                LearningLog.i(TAG, "Battery optimizations already ignored")
                return
            }
            binding.txtStatus.setText(R.string.status_battery_restricted)
            val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                data = Uri.parse("package:$packageName")
            }
            startActivity(intent)
            LearningLog.i(TAG, "Launched ignore battery optimizations")
        } catch (t: Throwable) {
            LearningLog.e(TAG, "requestIgnoreBatteryOptimizations failed", t)
        }
    }

    private fun refreshKeepAliveStatus() {
        val serviceOn = KeepAliveStore.isServiceEnabled(this)
        val listenOn = TelegramConfig.isListenEnabled(this)
        if (binding.txtStatus.text == getString(R.string.status_idle)) {
            binding.txtStatus.text = getString(
                R.string.status_keepalive,
                if (serviceOn) "ON" else "OFF",
                if (listenOn) "ON" else "OFF"
            )
        }
    }

    companion object {
        private const val TAG = "MainActivity"
    }
}
