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

    fun getBotToken(context: Context): String =
        prefs(context).getString(KEY_TOKEN, "")?.trim().orEmpty()

    fun setBotToken(context: Context, token: String) {
        prefs(context).edit().putString(KEY_TOKEN, token.trim()).apply()
        LearningLog.i(TAG, "Bot token saved (len=${token.trim().length})")
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
