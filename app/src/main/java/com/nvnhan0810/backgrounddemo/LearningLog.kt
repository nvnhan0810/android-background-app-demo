package com.nvnhan0810.backgrounddemo

import android.os.Handler
import android.os.Looper
import android.util.Log
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Bộ nhớ log trong app (learning only).
 * Newbie có thể đọc trên UI mà không cần mở Logcat.
 */
object LearningLog {

    enum class Level { D, I, W, E }

    data class Entry(
        val time: String,
        val level: Level,
        val tag: String,
        val message: String,
        val throwable: Throwable? = null
    ) {
        fun toDisplayLine(): String {
            val base = "$time ${level.name}/$tag: $message"
            val err = throwable ?: return base
            return base + "\n  ↳ " + (err.message ?: err::class.java.simpleName) +
                "\n  ↳ " + err.stackTraceToString().lineSequence().take(6).joinToString("\n  ↳ ")
        }
    }

    private const val MAX_ENTRIES = 250
    private val timeFormat = SimpleDateFormat("HH:mm:ss.SSS", Locale.getDefault())
    private val mainHandler = Handler(Looper.getMainLooper())

    private val entries = ArrayDeque<Entry>()
    private val listeners = mutableSetOf<(List<Entry>) -> Unit>()

    @Synchronized
    fun d(tag: String, message: String) = append(Level.D, tag, message)

    @Synchronized
    fun i(tag: String, message: String) = append(Level.I, tag, message)

    @Synchronized
    fun w(tag: String, message: String, throwable: Throwable? = null) =
        append(Level.W, tag, message, throwable)

    @Synchronized
    fun e(tag: String, message: String, throwable: Throwable? = null) =
        append(Level.E, tag, message, throwable)

    @Synchronized
    fun clear() {
        entries.clear()
        notifyListeners()
    }

    @Synchronized
    fun snapshot(): List<Entry> = entries.toList()

    @Synchronized
    fun addListener(listener: (List<Entry>) -> Unit) {
        listeners.add(listener)
        listener(entries.toList())
    }

    @Synchronized
    fun removeListener(listener: (List<Entry>) -> Unit) {
        listeners.remove(listener)
    }

    private fun append(
        level: Level,
        tag: String,
        message: String,
        throwable: Throwable? = null
    ) {
        val entry = Entry(
            time = timeFormat.format(Date()),
            level = level,
            tag = tag,
            message = message,
            throwable = throwable
        )
        entries.addLast(entry)
        while (entries.size > MAX_ENTRIES) {
            entries.removeFirst()
        }

        when (level) {
            Level.D -> Log.d(tag, message, throwable)
            Level.I -> Log.i(tag, message, throwable)
            Level.W -> Log.w(tag, message, throwable)
            Level.E -> Log.e(tag, message, throwable)
        }

        notifyListeners()
    }

    private fun notifyListeners() {
        val copy = entries.toList()
        val listenersCopy = listeners.toList()
        mainHandler.post {
            listenersCopy.forEach { it(copy) }
        }
    }
}
