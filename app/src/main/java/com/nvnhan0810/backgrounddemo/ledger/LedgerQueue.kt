package com.nvnhan0810.backgrounddemo.ledger

import com.nvnhan0810.backgrounddemo.LearningLog
import java.util.concurrent.Executors
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.ThreadFactory
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Hàng đợi single-writer: mọi ghi sổ cái đi tuần tự trên 1 thread.
 * Tránh xung đột khi nhiều tin / edit dồn dập.
 */
object LedgerQueue {

    sealed class Job {
        data class Inbound(
            val platform: String,
            val chatId: String,
            val messageId: String,
            val customerName: String,
            val rawText: String,
            val isEdit: Boolean,
            val onDone: (String) -> Unit
        ) : Job()

        data class Settle(
            val positions: Int,
            val onDone: (String) -> Unit
        ) : Job()

        data object Poison : Job()
    }

    private val running = AtomicBoolean(false)
    private val queue = LinkedBlockingQueue<Job>()

    @Volatile
    private var processor: LedgerProcessor? = null

    private val executor = Executors.newSingleThreadExecutor(
        ThreadFactory { r -> Thread(r, "ledger-writer").apply { isDaemon = true } }
    )

    fun start(processor: LedgerProcessor) {
        this.processor = processor
        if (running.compareAndSet(false, true)) {
            executor.execute { loop() }
            LearningLog.i(TAG, "LedgerQueue started")
        } else {
            LearningLog.d(TAG, "LedgerQueue already running — processor refreshed")
        }
    }

    fun stop() {
        running.set(false)
        queue.offer(Job.Poison)
        LearningLog.i(TAG, "LedgerQueue stop requested")
    }

    fun enqueueInbound(
        platform: String,
        chatId: String,
        messageId: String,
        customerName: String,
        rawText: String,
        isEdit: Boolean,
        onDone: (String) -> Unit = {}
    ) {
        ensureStartedHint()
        queue.offer(
            Job.Inbound(platform, chatId, messageId, customerName, rawText, isEdit, onDone)
        )
        LearningLog.d(
            TAG,
            "Enqueue ${if (isEdit) "EDIT" else "NEW"} chat=$chatId msg=$messageId qsize=${queue.size}"
        )
    }

    fun enqueueSettle(positions: Int, onDone: (String) -> Unit = {}) {
        ensureStartedHint()
        queue.offer(Job.Settle(positions, onDone))
    }

    fun settleAndWait(positions: Int, timeoutMs: Long = 5_000): String {
        val box = java.util.concurrent.ArrayBlockingQueue<String>(1)
        enqueueSettle(positions) { box.offer(it) }
        return box.poll(timeoutMs, TimeUnit.MILLISECONDS)
            ?: "Timeout chờ hàng đợi tất toán"
    }

    private fun ensureStartedHint() {
        if (!running.get()) {
            LearningLog.w(TAG, "Queue chưa start — job sẽ chờ đến khi start()")
        }
    }

    private fun loop() {
        while (running.get()) {
            try {
                val job = queue.poll(1, TimeUnit.SECONDS) ?: continue
                if (job is Job.Poison) break
                val p = processor
                if (p == null) {
                    LearningLog.w(TAG, "No processor — drop job")
                    continue
                }
                when (job) {
                    is Job.Inbound -> {
                        try {
                            val reply = p.applyInbound(
                                platform = job.platform,
                                chatId = job.chatId,
                                messageId = job.messageId,
                                customerName = job.customerName,
                                rawText = job.rawText,
                                isEdit = job.isEdit
                            )
                            job.onDone(reply)
                        } catch (t: Throwable) {
                            LearningLog.e(TAG, "Inbound job failed", t)
                            job.onDone("ERR | ${t.message}")
                        }
                    }
                    is Job.Settle -> {
                        try {
                            val reply = p.settlePositions(job.positions)
                            job.onDone(reply)
                        } catch (t: Throwable) {
                            LearningLog.e(TAG, "Settle job failed", t)
                            job.onDone("ERR | ${t.message}")
                        }
                    }
                    Job.Poison -> break
                }
            } catch (t: Throwable) {
                LearningLog.e(TAG, "LedgerQueue loop error", t)
            }
        }
        running.set(false)
        LearningLog.i(TAG, "LedgerQueue loop ended")
    }

    private const val TAG = "LedgerQueue"
}
