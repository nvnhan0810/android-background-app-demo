package com.nvnhan0810.backgrounddemo

import android.content.Context

/**
 * Lưu ý muốn “keep alive” trên máy (SharedPreferences = file key/value local).
 * Sau reboot / process chết, đọc lại flag để tự bật service + WorkManager.
 */
object KeepAliveStore {

    private const val PREFS = "keep_alive_prefs"
    private const val KEY_SERVICE = "service_enabled"
    private const val KEY_WORK = "work_enabled"

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun setServiceEnabled(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean(KEY_SERVICE, enabled).apply()
        LearningLog.i(TAG, "Persist service_enabled=$enabled")
    }

    fun isServiceEnabled(context: Context): Boolean =
        prefs(context).getBoolean(KEY_SERVICE, false)

    fun setWorkEnabled(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean(KEY_WORK, enabled).apply()
        LearningLog.i(TAG, "Persist work_enabled=$enabled")
    }

    fun isWorkEnabled(context: Context): Boolean =
        prefs(context).getBoolean(KEY_WORK, false)

    private const val TAG = "KeepAliveStore"
}
