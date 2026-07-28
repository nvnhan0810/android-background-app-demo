package com.nvnhan0810.backgrounddemo.telegram

import android.content.Context
import com.nvnhan0810.backgrounddemo.LearningLog

/**
 * Cấu hình Telegram + UI ledger (SharedPreferences = key/value local).
 */
object TelegramConfig {

    private const val PREFS = "telegram_ledger_prefs"

    private const val KEY_TOKEN = "bot_token"
    private const val KEY_OFFSET = "update_offset"
    private const val KEY_RATIO = "price_ratio"
    private const val KEY_SELECTED_CHAT = "selected_chat_id"
    private const val KEY_SELECTED_NAME = "selected_customer_name"
    private const val KEY_LISTEN = "listen_enabled"

    /** Ba mức tỷ lệ demo */
    val RATIO_OPTIONS = doubleArrayOf(1.0, 1.2, 1.5)

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /**
     * Token từ BotFather dạng `123456:AA...`.
     * Khi paste từ điện thoại hay dính khoảng trắng / xuống dòng → Telegram trả HTTP 401.
     */
    fun sanitizeToken(raw: String): String =
        raw.trim()
            .replace("\uFEFF", "") // BOM
            .replace(Regex("\\s+"), "")

    fun getBotToken(context: Context): String =
        sanitizeToken(prefs(context).getString(KEY_TOKEN, "").orEmpty())

    fun setBotToken(context: Context, token: String) {
        val clean = sanitizeToken(token)
        val old = getBotToken(context)
        prefs(context).edit().putString(KEY_TOKEN, clean).apply()
        LearningLog.i(
            TAG,
            "Bot token saved len=${clean.length} looksLikeToken=${looksLikeBotToken(clean)}"
        )
        // Đổi token → reset offset để không “nhảy” queue cũ của bot khác.
        if (old.isNotEmpty() && old != clean) {
            setUpdateOffset(context, 0L)
            LearningLog.i(TAG, "Token changed — reset update offset=0")
        }
    }

    fun looksLikeBotToken(token: String): Boolean {
        val parts = token.split(':', limit = 2)
        if (parts.size != 2) return false
        val id = parts[0]
        val secret = parts[1]
        return id.length >= 5 && id.all { it.isDigit() } &&
            secret.length >= 20 &&
            secret.all { it.isLetterOrDigit() || it == '_' || it == '-' }
    }

    fun getUpdateOffset(context: Context): Long =
        prefs(context).getLong(KEY_OFFSET, 0L)

    fun setUpdateOffset(context: Context, offset: Long) {
        prefs(context).edit().putLong(KEY_OFFSET, offset).apply()
    }

    fun getPriceRatio(context: Context): Double {
        val v = prefs(context).getFloat(KEY_RATIO, 1.0f).toDouble()
        return RATIO_OPTIONS.minByOrNull { kotlin.math.abs(it - v) } ?: 1.0
    }

    fun setPriceRatio(context: Context, ratio: Double) {
        prefs(context).edit().putFloat(KEY_RATIO, ratio.toFloat()).apply()
        LearningLog.i(TAG, "Price ratio=$ratio")
    }

    fun getSelectedChatId(context: Context): String =
        prefs(context).getString(KEY_SELECTED_CHAT, "")?.orEmpty() ?: ""

    fun getSelectedCustomerName(context: Context): String =
        prefs(context).getString(KEY_SELECTED_NAME, "")?.orEmpty() ?: ""

    fun setSelectedCustomer(context: Context, chatId: String, name: String) {
        prefs(context).edit()
            .putString(KEY_SELECTED_CHAT, chatId)
            .putString(KEY_SELECTED_NAME, name)
            .apply()
        LearningLog.i(TAG, "Selected customer chatId=$chatId name=$name")
    }

    fun isListenEnabled(context: Context): Boolean =
        prefs(context).getBoolean(KEY_LISTEN, false)

    fun setListenEnabled(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean(KEY_LISTEN, enabled).apply()
        LearningLog.i(TAG, "Listen enabled=$enabled")
    }

    private const val TAG = "TelegramConfig"
}
