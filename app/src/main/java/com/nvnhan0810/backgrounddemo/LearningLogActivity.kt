package com.nvnhan0810.backgrounddemo

import android.content.ClipData
import android.content.ClipboardManager
import android.os.Bundle
import android.util.TypedValue
import android.widget.Toast
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding
import com.nvnhan0810.backgrounddemo.databinding.ActivityLearningLogBinding

/**
 * Trang riêng cho LearningLog — scroll dễ, không dính select-text.
 * Giữ lâu trên text → copy toàn bộ log.
 */
class LearningLogActivity : AppCompatActivity() {

    private lateinit var binding: ActivityLearningLogBinding

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

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        binding = ActivityLearningLogBinding.inflate(layoutInflater)
        setContentView(binding.root)
        applySafeAreaInsets()

        LearningLog.addListener(logListener)
        LearningLog.i(TAG, "LearningLogActivity onCreate")

        binding.btnBack.setOnClickListener { finish() }
        binding.btnClearLog.setOnClickListener {
            LearningLog.clear()
            LearningLog.i(TAG, "Log cleared")
        }
        binding.txtLearningLog.setOnLongClickListener {
            val text = binding.txtLearningLog.text?.toString().orEmpty()
            val cm = getSystemService(ClipboardManager::class.java)
            cm.setPrimaryClip(ClipData.newPlainText("learning_log", text))
            Toast.makeText(this, R.string.log_copied, Toast.LENGTH_SHORT).show()
            true
        }
    }

    private fun applySafeAreaInsets() {
        val pad = TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_DIP,
            8f,
            resources.displayMetrics
        ).toInt()
        ViewCompat.setOnApplyWindowInsetsListener(binding.root) { view, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            view.updatePadding(
                left = bars.left + pad,
                top = bars.top + pad,
                right = bars.right + pad,
                bottom = bars.bottom + pad
            )
            insets
        }
        ViewCompat.requestApplyInsets(binding.root)
    }

    override fun onDestroy() {
        LearningLog.removeListener(logListener)
        super.onDestroy()
    }

    companion object {
        private const val TAG = "LearningLogActivity"
    }
}
